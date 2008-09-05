/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapred;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ChecksumFileSystem;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableFactories;
import org.apache.hadoop.io.WritableFactory;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.io.compress.CodecPool;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.mapred.IFile.*;
import org.apache.hadoop.mapred.Merger.Segment;
import org.apache.hadoop.mapred.SortedRanges.SkipRangeIterator;
import org.apache.hadoop.metrics.MetricsContext;
import org.apache.hadoop.metrics.MetricsRecord;
import org.apache.hadoop.metrics.MetricsUtil;
import org.apache.hadoop.metrics.Updater;
import org.apache.hadoop.net.ConnTimeoutException;
import org.apache.hadoop.net.ReadTimeoutException;
import org.apache.hadoop.util.Progress;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;

/** A Reduce task. */
class ReduceTask extends Task {

  static {                                        // register a ctor
    WritableFactories.setFactory
      (ReduceTask.class,
       new WritableFactory() {
         public Writable newInstance() { return new ReduceTask(); }
       });
  }
  
  private static final Log LOG = LogFactory.getLog(ReduceTask.class.getName());
  private int numMaps;
  private ReduceCopier reduceCopier;

  private static enum CopyOutputErrorType {
	  NO_ERROR,
	  CONNECTION_ERROR,
	  READ_ERROR
  }

  private CompressionCodec codec;


  { 
    getProgress().setStatus("reduce"); 
    setPhase(TaskStatus.Phase.SHUFFLE);        // phase to start with 
  }


  private Progress copyPhase = getProgress().addPhase("copy");
  private Progress sortPhase  = getProgress().addPhase("sort");
  private Progress reducePhase = getProgress().addPhase("reduce");
  private Counters.Counter reduceInputKeyCounter = 
    getCounters().findCounter(Counter.REDUCE_INPUT_GROUPS);
  private Counters.Counter reduceInputValueCounter = 
    getCounters().findCounter(Counter.REDUCE_INPUT_RECORDS);
  private Counters.Counter reduceOutputCounter = 
    getCounters().findCounter(Counter.REDUCE_OUTPUT_RECORDS);
  private Counters.Counter reduceCombineInputCounter =
    getCounters().findCounter(Counter.COMBINE_INPUT_RECORDS);
  private Counters.Counter reduceCombineOutputCounter =
    getCounters().findCounter(Counter.COMBINE_OUTPUT_RECORDS);

  // A custom comparator for map output files. Here the ordering is determined
  // by the file's size and path. In case of files with same size and different
  // file paths, the first parameter is considered smaller than the second one.
  // In case of files with same size and path are considered equal.
  private Comparator<FileStatus> mapOutputFileComparator = 
    new Comparator<FileStatus>() {
      public int compare(FileStatus a, FileStatus b) {
        if (a.getLen() < b.getLen())
          return -1;
        else if (a.getLen() == b.getLen())
          if (a.getPath().toString().equals(b.getPath().toString()))
            return 0;
          else
            return -1; 
        else
          return 1;
      }
  };
  
  // A sorted set for keeping a set of map output files on disk
  private final SortedSet<FileStatus> mapOutputFilesOnDisk = 
    new TreeSet<FileStatus>(mapOutputFileComparator);

  public ReduceTask() {
    super();
  }

  public ReduceTask(String jobFile, TaskAttemptID taskId,
                    int partition, int numMaps) {
    super(jobFile, taskId, partition);
    this.numMaps = numMaps;
  }
  
  private CompressionCodec initCodec() {
    // check if map-outputs are to be compressed
    if (conf.getCompressMapOutput()) {
      Class<? extends CompressionCodec> codecClass =
        conf.getMapOutputCompressorClass(DefaultCodec.class);
      return ReflectionUtils.newInstance(codecClass, conf);
    } 

    return null;
  }

  @Override
  public TaskRunner createRunner(TaskTracker tracker) throws IOException {
    return new ReduceTaskRunner(this, tracker, this.conf);
  }

  @Override
  public boolean isMapTask() {
    return false;
  }

  public int getNumMaps() { return numMaps; }
  
  /**
   * Localize the given JobConf to be specific for this task.
   */
  @Override
  public void localizeConfiguration(JobConf conf) throws IOException {
    super.localizeConfiguration(conf);
    conf.setNumMapTasks(numMaps);
  }

  @Override
  public void write(DataOutput out) throws IOException {
    super.write(out);

    out.writeInt(numMaps);                        // write the number of maps
  }

  @Override
  public void readFields(DataInput in) throws IOException {
    super.readFields(in);

    numMaps = in.readInt();
  }
  
  // Get the input files for the reducer.
  private Path[] getMapFiles(FileSystem fs, boolean isLocal) 
  throws IOException {
    List<Path> fileList = new ArrayList<Path>();
    if (isLocal) {
      // for local jobs
      for(int i = 0; i < numMaps; ++i) {
        fileList.add(mapOutputFile.getInputFile(i, getTaskID()));
      }
    } else {
      // for non local jobs
      for (FileStatus filestatus : mapOutputFilesOnDisk) {
        fileList.add(filestatus.getPath());
      }
    }
    return fileList.toArray(new Path[0]);
  }

  private class ReduceValuesIterator<KEY,VALUE> 
          extends ValuesIterator<KEY,VALUE> {
    public ReduceValuesIterator (RawKeyValueIterator in,
                                 RawComparator<KEY> comparator, 
                                 Class<KEY> keyClass,
                                 Class<VALUE> valClass,
                                 Configuration conf, Progressable reporter)
      throws IOException {
      super(in, comparator, keyClass, valClass, conf, reporter);
    }

    @Override
    public VALUE next() {
      reduceInputValueCounter.increment(1);
      return moveToNext();
    }
    
    protected VALUE moveToNext() {
      return super.next();
    }
    
    public void informReduceProgress() {
      reducePhase.set(super.in.getProgress().get()); // update progress
      reporter.progress();
    }
  }

  private class SkippingReduceValuesIterator<KEY,VALUE> 
     extends ReduceValuesIterator<KEY,VALUE> {
     private SkipRangeIterator skipIt;
     private TaskUmbilicalProtocol umbilical;
     private Counters.Counter skipGroupCounter;
     private Counters.Counter skipRecCounter;
     private long recIndex = -1;
     private Class<KEY> keyClass;
     private Class<VALUE> valClass;
     private SequenceFile.Writer skipWriter;
     
     public SkippingReduceValuesIterator(RawKeyValueIterator in,
         RawComparator<KEY> comparator, Class<KEY> keyClass,
         Class<VALUE> valClass, Configuration conf, Progressable reporter,
         TaskUmbilicalProtocol umbilical) throws IOException {
       super(in, comparator, keyClass, valClass, conf, reporter);
       this.umbilical = umbilical;
       this.skipGroupCounter = 
         getCounters().findCounter(Counter.REDUCE_SKIPPED_GROUPS);
       this.skipRecCounter = 
         getCounters().findCounter(Counter.REDUCE_SKIPPED_RECORDS);
       this.keyClass = keyClass;
       this.valClass = valClass;
       skipIt = getFailedRanges().skipRangeIterator();
       mayBeSkip();
     }
     
     void nextKey() throws IOException {
       super.nextKey();
       mayBeSkip();
     }
     
     private void mayBeSkip() throws IOException {
       recIndex++;
       long nextRecIndex = skipIt.next();
       long skip = nextRecIndex - recIndex;
       long skipRec = 0;
       for(int i=0;i<skip && super.more();i++) {
         while (hasNext()) {
           writeSkippedRec(getKey(), moveToNext());
           skipRec++;
         }
         super.nextKey();
         recIndex++;
       }
       //close the skip writer once all the ranges are skipped
       if(skip>0 && skipIt.skippedAllRanges() && skipWriter!=null) {
         skipWriter.close();
       }
       skipGroupCounter.increment(skip);
       skipRecCounter.increment(skipRec);
       reportNextRecordRange(umbilical, nextRecIndex);
     }
     
     @SuppressWarnings("unchecked")
     private void writeSkippedRec(KEY key, VALUE value) throws IOException{
       if(skipWriter==null) {
         Path skipDir = SkipBadRecords.getSkipOutputPath(conf);
         Path skipFile = new Path(skipDir, getTaskID().toString());
         skipWriter = SequenceFile.createWriter(
               skipFile.getFileSystem(conf), conf, skipFile,
               keyClass, valClass, 
               CompressionType.BLOCK, getReporter(umbilical));
       }
       skipWriter.append(key, value);
     }
  }

  @Override
  @SuppressWarnings("unchecked")
  public void run(JobConf job, final TaskUmbilicalProtocol umbilical)
    throws IOException {
    job.setBoolean("mapred.skip.on", isSkipping());
    Reducer reducer = ReflectionUtils.newInstance(job.getReducerClass(), job);

    // start thread that will handle communication with parent
    startCommunicationThread(umbilical);
    final Reporter reporter = getReporter(umbilical);
    initialize(job, reporter);

    // check if it is a cleanupJobTask
    if (cleanupJob) {
      runCleanup(umbilical);
      return;
    }
    
    FileSystem lfs = FileSystem.getLocal(job);
    
    // Initialize the codec
    codec = initCodec();

    boolean isLocal = true;
    if (!job.get("mapred.job.tracker", "local").equals("local")) {
      reduceCopier = new ReduceCopier(umbilical, job);
      if (!reduceCopier.fetchOutputs()) {
        throw new IOException(getTaskID() + "The reduce copier failed");
      }
      isLocal = false;
    }
    copyPhase.complete();                         // copy is already complete
    

    // get the input files for the reducer to merge
    Path[] mapFiles = getMapFiles(lfs, isLocal);
    
    Path tempDir = new Path(getTaskID().toString()); 
 
    setPhase(TaskStatus.Phase.SORT); 

    
    // sort the input file
    LOG.info("Initiating final on-disk merge with " + mapFiles.length + 
             " files");
    RawKeyValueIterator rIter = 
      Merger.merge(job, lfs,
                   job.getMapOutputKeyClass(), job.getMapOutputValueClass(),
                   codec, mapFiles, !conf.getKeepFailedTaskFiles(), 
                   job.getInt("io.sort.factor", 100), tempDir, 
                   job.getOutputKeyComparator(), reporter); 
        
    // free up the data structures
    mapOutputFilesOnDisk.clear();
    mapFiles = null;
    
    sortPhase.complete();                         // sort is complete
    setPhase(TaskStatus.Phase.REDUCE); 

    // make output collector
    String finalName = getOutputName(getPartition());

    FileSystem fs = FileSystem.get(job);

    final RecordWriter out = 
      job.getOutputFormat().getRecordWriter(fs, job, finalName, reporter);  
    
    OutputCollector collector = new OutputCollector() {
        public void collect(Object key, Object value)
          throws IOException {
          out.write(key, value);
          reduceOutputCounter.increment(1);
          // indicate that progress update needs to be sent
          reporter.progress();
        }
      };
    
    // apply reduce function
    try {
      Class keyClass = job.getMapOutputKeyClass();
      Class valClass = job.getMapOutputValueClass();
      boolean incrProcCount = isSkipping() &&
        SkipBadRecords.getAutoIncrReducerProcCount(job);
      
      ReduceValuesIterator values = isSkipping() ? 
          new SkippingReduceValuesIterator(rIter, 
              job.getOutputValueGroupingComparator(), keyClass, valClass, 
              job, reporter, umbilical) :
          new ReduceValuesIterator(rIter, 
          job.getOutputValueGroupingComparator(), keyClass, valClass, 
          job, reporter);
      values.informReduceProgress();
      while (values.more()) {
        reduceInputKeyCounter.increment(1);
        reducer.reduce(values.getKey(), values, collector, reporter);
        if(incrProcCount) {
          reporter.incrCounter(Counters.Application.GROUP, 
              Counters.Application.REDUCE_PROCESSED_RECORDS, 1);
        }
        values.nextKey();
        values.informReduceProgress();
      }

      //Clean up: repeated in catch block below
      reducer.close();
      out.close(reporter);
      //End of clean up.
    } catch (IOException ioe) {
      try {
        reducer.close();
      } catch (IOException ignored) {}
        
      try {
        out.close(reporter);
      } catch (IOException ignored) {}
      
      throw ioe;
    }
    done(umbilical);
  }

  class ReduceCopier<K, V> implements MRConstants {

    /** Reference to the umbilical object */
    private TaskUmbilicalProtocol umbilical;
    
    /** Reference to the task object */
    
    /** Number of ms before timing out a copy */
    private static final int STALLED_COPY_TIMEOUT = 3 * 60 * 1000;
    
    /** Max events to fetch in one go from the tasktracker */
    private static final int MAX_EVENTS_TO_FETCH = 10000;

    /**
     * our reduce task instance
     */
    private ReduceTask reduceTask;
    
    /**
     * the list of map outputs currently being copied
     */
    private List<MapOutputLocation> scheduledCopies;
    
    /**
     *  the results of dispatched copy attempts
     */
    private List<CopyResult> copyResults;
    
    /**
     *  the number of outputs to copy in parallel
     */
    private int numCopiers;
    
    /**
     *  a number that is set to the max #fetches we'd schedule and then
     *  pause the schduling
     */
    private int maxInFlight;
    
    /**
     * the amount of time spent on fetching one map output before considering 
     * it as failed and notifying the jobtracker about it.
     */
    private int maxBackoff;
    
    /**
     * busy hosts from which copies are being backed off
     * Map of host -> next contact time
     */
    private Map<String, Long> penaltyBox;
    
    /**
     * the set of unique hosts from which we are copying
     */
    private Set<String> uniqueHosts;
    
    /**
     * the last time we polled the job tracker
     */
    private long lastPollTime;
    
    /**
     * A reference to the RamManager for writing the map outputs to.
     */
    
    private ShuffleRamManager ramManager;
    
    /**
     * A reference to the local file system for writing the map outputs to.
     */
    private FileSystem localFileSys;

    /**
     * Number of files to merge at a time
     */
    private int ioSortFactor;
    
    /**
     * A reference to the throwable object (if merge throws an exception)
     */
    private volatile Throwable mergeThrowable;
    
    /** 
     * A flag to indicate when to exit localFS merge
     */
    private volatile boolean exitLocalFSMerge = false;
    
    /**
     * When we accumulate mergeThreshold number of files in ram, we merge/spill
     */
    private int mergeThreshold = 500;
    
    /**
     * The threads for fetching the files.
     */
    private List<MapOutputCopier> copiers = null;
    
    /**
     * The object for metrics reporting.
     */
    private ShuffleClientMetrics shuffleClientMetrics = null;
    
    /**
     * the minimum interval between tasktracker polls
     */
    private static final long MIN_POLL_INTERVAL = 1000;
    
    /**
     * a list of map output locations for fetch retrials 
     */
    private List<MapOutputLocation> retryFetches =
      new ArrayList<MapOutputLocation>();
    
    /** 
     * The set of required map outputs
     */
    private Set <TaskID> copiedMapOutputs = 
      Collections.synchronizedSet(new TreeSet<TaskID>());
    
    /** 
     * The set of obsolete map taskids.
     */
    private Set <TaskAttemptID> obsoleteMapIds = 
      Collections.synchronizedSet(new TreeSet<TaskAttemptID>());
    
    private Random random = null;
    
    /**
     * the max size of the merge output from ramfs
     */
    private long ramfsMergeOutputSize;
    
    /**
     * the max of all the map completion times
     */
    private int maxMapRuntime;
    
    /**
     * Maximum number of fetch-retries per-map.
     */
    private int maxFetchRetriesPerMap;
    
    /**
     * Combiner class to run during in-memory merge, if defined.
     */
    private final Class<? extends Reducer> combinerClass;

    /**
     * Resettable collector used for combine.
     */
    private final CombineOutputCollector combineCollector;

    /**
     * Maximum percent of failed fetch attempt before killing the reduce task.
     */
    private static final float MAX_ALLOWED_FAILED_FETCH_ATTEMPT_PERCENT = 0.5f;

    /**
     * Minimum percent of progress required to keep the reduce alive.
     */
    private static final float MIN_REQUIRED_PROGRESS_PERCENT = 0.5f;

    /**
     * Maximum percent of shuffle execution time required to keep the reducer alive.
     */
    private static final float MAX_ALLOWED_STALL_TIME_PERCENT = 0.5f;

    /**
     * Maximum no. of unique maps from which we failed to fetch map-outputs
     * even after {@link #maxFetchRetriesPerMap} retries; after this the
     * reduce task is failed.
     */
    private static final int MAX_FAILED_UNIQUE_FETCHES = 5;

    /**
     * The maps from which we fail to fetch map-outputs 
     * even after {@link #maxFetchRetriesPerMap} retries.
     */
    Set<TaskID> fetchFailedMaps = new TreeSet<TaskID>(); 
    
    /**
     * A map of taskId -> no. of failed fetches in connect
     */
    Map<TaskAttemptID, Integer> mapTaskToConnectFailedFetchesMap = 
      new HashMap<TaskAttemptID, Integer>();    

    /**
     * A map of taskId -> no. of failed fetches in read
     */
    Map<TaskAttemptID, Integer> mapTaskToReadFailedFetchesMap = 
      new HashMap<TaskAttemptID, Integer>();    

    /**
     * Initial backoff interval (milliseconds)
     */
    private static final int BACKOFF_INIT = 4000; 
    
    /**
     * The interval for logging in the shuffle
     */
    private static final int MIN_LOG_TIME = 60000;

    /** 
     * List of in-memory map-outputs.
     */
    private final List<MapOutput> mapOutputsFilesInMemory =
      Collections.synchronizedList(new LinkedList<MapOutput>());
    


    /**
     * This class contains the methods that should be used for metrics-reporting
     * the specific metrics for shuffle. This class actually reports the
     * metrics for the shuffle client (the ReduceTask), and hence the name
     * ShuffleClientMetrics.
     */
    class ShuffleClientMetrics implements Updater {
      private MetricsRecord shuffleMetrics = null;
      private int numFailedFetches = 0;
      private int numSuccessFetches = 0;
      private long numBytes = 0;
      private int numThreadsBusy = 0;
      ShuffleClientMetrics(JobConf conf) {
        MetricsContext metricsContext = MetricsUtil.getContext("mapred");
        this.shuffleMetrics = 
          MetricsUtil.createRecord(metricsContext, "shuffleInput");
        this.shuffleMetrics.setTag("user", conf.getUser());
        this.shuffleMetrics.setTag("jobName", conf.getJobName());
        this.shuffleMetrics.setTag("jobId", ReduceTask.this.getJobID().toString());
        this.shuffleMetrics.setTag("taskId", getTaskID().toString());
        this.shuffleMetrics.setTag("sessionId", conf.getSessionId());
        metricsContext.registerUpdater(this);
      }
      public synchronized void inputBytes(long numBytes) {
        this.numBytes += numBytes;
      }
      public synchronized void failedFetch() {
        ++numFailedFetches;
      }
      public synchronized void successFetch() {
        ++numSuccessFetches;
      }
      public synchronized void threadBusy() {
        ++numThreadsBusy;
      }
      public synchronized void threadFree() {
        --numThreadsBusy;
      }
      public void doUpdates(MetricsContext unused) {
        synchronized (this) {
          shuffleMetrics.incrMetric("shuffle_input_bytes", numBytes);
          shuffleMetrics.incrMetric("shuffle_failed_fetches", 
                                    numFailedFetches);
          shuffleMetrics.incrMetric("shuffle_success_fetches", 
                                    numSuccessFetches);
          if (numCopiers != 0) {
            shuffleMetrics.setMetric("shuffle_fetchers_busy_percent",
                100*((float)numThreadsBusy/numCopiers));
          } else {
            shuffleMetrics.setMetric("shuffle_fetchers_busy_percent", 0);
          }
          numBytes = 0;
          numSuccessFetches = 0;
          numFailedFetches = 0;
        }
        shuffleMetrics.update();
      }
    }

    /** Represents the result of an attempt to copy a map output */
    private class CopyResult {
      

    	// the map output location against which a copy attempt was made
      private final MapOutputLocation loc;
      
      // the size of the file copied, -1 if the transfer failed
      private final long size;
      
      //a flag signifying whether a copy result is obsolete
      private static final int OBSOLETE = -2;

      CopyOutputErrorType errorType;

      CopyResult(MapOutputLocation loc, long size, 
    		     CopyOutputErrorType errorType) {
        this.loc = loc;
        this.size = size;
        this.errorType = errorType;
      }
      
      public boolean getSuccess() { return size >= 0; }
      public boolean isObsolete() { 
        return size == OBSOLETE;
      }
      public long getSize() { return size; }
      public String getHost() { return loc.getHost(); }
      public CopyOutputErrorType getErrorType() {
    	  return ((size < 0) ? errorType: CopyOutputErrorType.NO_ERROR); 
      }
      public MapOutputLocation getLocation() { return loc; }
    }
    
    private int nextMapOutputCopierId = 0;
    
    /**
     * Abstraction to track a map-output.
     */
    private class MapOutputLocation {
      TaskAttemptID taskAttemptId;
      TaskID taskId;
      String ttHost;
      URL taskOutput;
      
      public MapOutputLocation(TaskAttemptID taskAttemptId, 
                               String ttHost, URL taskOutput) {
        this.taskAttemptId = taskAttemptId;
        this.taskId = this.taskAttemptId.getTaskID();
        this.ttHost = ttHost;
        this.taskOutput = taskOutput;
      }
      
      public TaskAttemptID getTaskAttemptId() {
        return taskAttemptId;
      }
      
      public TaskID getTaskId() {
        return taskId;
      }
      
      public String getHost() {
        return ttHost;
      }
      
      public URL getOutputLocation() {
        return taskOutput;
      }
    }
    
    /** Describes the output of a map; could either be on disk or in-memory. */
    private class MapOutput {
      final TaskID mapId;
      final TaskAttemptID mapAttemptId;
      
      final Path file;
      final Configuration conf;
      
      byte[] data;
      final boolean inMemory;
      long size;
      
      public MapOutput(TaskID mapId, TaskAttemptID mapAttemptId, 
                       Configuration conf, Path file, long size) {
        this.mapId = mapId;
        this.mapAttemptId = mapAttemptId;
        
        this.conf = conf;
        this.file = file;
        this.size = size;
        
        this.data = null;
        
        this.inMemory = false;
      }
      
      public MapOutput(TaskID mapId, TaskAttemptID mapAttemptId, byte[] data) {
        this.mapId = mapId;
        this.mapAttemptId = mapAttemptId;
        
        this.file = null;
        this.conf = null;
        
        this.data = data;
        this.size = data.length;
        
        this.inMemory = true;
      }
      
      public void discard() throws IOException {
        if (inMemory) {
          data = null;
        } else {
          FileSystem fs = file.getFileSystem(conf);
          fs.delete(file, true);
        }
      }
    }
    
    class ShuffleRamManager implements RamManager {
      /* Maximum percentage of the in-memory limit that a single shuffle can 
       * consume*/ 
      private static final float MAX_SINGLE_SHUFFLE_SEGMENT_FRACTION = 0.25f;
      
      /* Maximum percentage of shuffle-threads which can be stalled 
       * simultaneously after which a merge is triggered. */ 
      private static final float MAX_STALLED_SHUFFLE_THREADS_FRACTION = 0.75f;
      
      private final int maxSize;
      private final int maxSingleShuffleLimit;
      
      private int size = 0;
      
      private Object dataAvailable = new Object();
      private int fullSize = 0;
      private int numPendingRequests = 0;
      private int numRequiredMapOutputs = 0;
      private int numClosed = 0;
      private boolean closed = false;
      
      public ShuffleRamManager(Configuration conf) {
        maxSize = conf.getInt("fs.inmemory.size.mb", 100) * 1024 * 1024;
        maxSingleShuffleLimit = (int)(maxSize * MAX_SINGLE_SHUFFLE_SEGMENT_FRACTION);
        LOG.info("ShuffleRamManager: MemoryLimit=" + maxSize + 
                 ", MaxSingleShuffleLimit=" + maxSingleShuffleLimit);
      }
      
      public synchronized boolean reserve(int requestedSize, InputStream in) 
      throws InterruptedException {
        // Wait till the request can be fulfilled...
        while ((size + requestedSize) > maxSize) {
          
          // Close the input...
          if (in != null) {
            try {
              in.close();
            } catch (IOException ie) {
              LOG.info("Failed to close connection with: " + ie);
            } finally {
              in = null;
            }
          } 

          // Track pending requests
          synchronized (dataAvailable) {
            ++numPendingRequests;
            dataAvailable.notify();
          }

          // Wait for memory to free up
          wait();
          
          // Track pending requests
          synchronized (dataAvailable) {
            --numPendingRequests;
          }
        }
        
        size += requestedSize;
        
        return (in != null);
      }
      
      public synchronized void unreserve(int requestedSize) {
        size -= requestedSize;
        
        synchronized (dataAvailable) {
          fullSize -= requestedSize;
          --numClosed;
        }
        
        // Notify the threads blocked on RamManager.reserve
        notifyAll();
      }
      
      public boolean waitForDataToMerge() throws InterruptedException {
        boolean done = false;
        synchronized (dataAvailable) {
                 // Start in-memory merge if manager has been closed or...
          while (!closed
                 &&
                 // In-memory threshold exceeded and at least two segments
                 // have been fetched
                 (getPercentUsed() < MAX_INMEM_FILESYS_USE ||
                  numClosed < 
                    (int)(MAX_INMEM_FILESYS_USE/MAX_INMEM_FILESIZE_FRACTION)
                 ) 
                 &&
                 // More than "mapred.inmem.merge.threshold" map outputs
                 // have been fetched into memory
                 (mergeThreshold <= 0 || numClosed < mergeThreshold) 
                 && 
                 // More than MAX... threads are blocked on the RamManager
                 // or the blocked threads are the last map outputs to be
                 // fetched. If numRequiredMapOutputs is zero, either
                 // setNumCopiedMapOutputs has not been called (no map ouputs
                 // have been fetched, so there is nothing to merge) or the
                 // last map outputs being transferred without
                 // contention, so a merge would be premature.
                 (numPendingRequests < 
                      numCopiers*MAX_STALLED_SHUFFLE_THREADS_FRACTION && 
                  (0 == numRequiredMapOutputs ||
                   numPendingRequests < numRequiredMapOutputs))) {
            dataAvailable.wait();
          }
          done = closed;
        }
        return done;
      }
      
      public void closeInMemoryFile(int requestedSize) {
        synchronized (dataAvailable) {
          fullSize += requestedSize;
          ++numClosed;
          dataAvailable.notify();
        }
      }
      
      public void setNumCopiedMapOutputs(int numRequiredMapOutputs) {
        synchronized (dataAvailable) {
          this.numRequiredMapOutputs = numRequiredMapOutputs;
          dataAvailable.notify();
        }
      }
      
      public void close() {
        synchronized (dataAvailable) {
          closed = true;
          LOG.info("Closed ram manager");
          dataAvailable.notify();
        }
      }
      
      private float getPercentUsed() {
        return (float)fullSize/maxSize;
      }

      int getMemoryLimit() {
        return maxSize;
      }
      
      boolean canFitInMemory(long requestedSize) {
        return (requestedSize < Integer.MAX_VALUE && 
                requestedSize < maxSingleShuffleLimit);
      }
    }

    /** Copies map outputs as they become available */
    private class MapOutputCopier extends Thread {
      // basic/unit connection timeout (in milliseconds)
      private final static int UNIT_CONNECT_TIMEOUT = 30 * 1000;
      // default read timeout (in milliseconds)
      private final static int DEFAULT_READ_TIMEOUT = 3 * 60 * 1000;

      private MapOutputLocation currentLocation = null;
      private int id = nextMapOutputCopierId++;
      private Reporter reporter;
      
      // Decompression of map-outputs
      private CompressionCodec codec = null;
      private Decompressor decompressor = null;
      
      public MapOutputCopier(JobConf job, Reporter reporter) {
        setName("MapOutputCopier " + reduceTask.getTaskID() + "." + id);
        LOG.debug(getName() + " created");
        this.reporter = reporter;
        
        if (job.getCompressMapOutput()) {
          Class<? extends CompressionCodec> codecClass =
            job.getMapOutputCompressorClass(DefaultCodec.class);
          codec = ReflectionUtils.newInstance(codecClass, job);
          decompressor = CodecPool.getDecompressor(codec);
        }
      }
      
      /**
       * Get the current map output location.
       */
      public synchronized MapOutputLocation getLocation() {
        return currentLocation;
      }
      
      private synchronized void start(MapOutputLocation loc) {
        currentLocation = loc;
      }
      
      private synchronized void finish(long size, 
    		                           CopyOutputErrorType errorType) {
        if (currentLocation != null) {
          LOG.debug(getName() + " finishing " + currentLocation + " =" + size);
          synchronized (copyResults) {
            copyResults.add(new CopyResult(currentLocation, size, errorType));
            copyResults.notify();
          }
          currentLocation = null;
        }
      }
      
      /** Loop forever and fetch map outputs as they become available.
       * The thread exits when it is interrupted by {@link ReduceTaskRunner}
       */
      @Override
      public void run() {
        while (true) {        
          try {
            MapOutputLocation loc = null;
            long size = -1;
            CopyOutputErrorType errorType = CopyOutputErrorType.NO_ERROR;
            
            synchronized (scheduledCopies) {
              while (scheduledCopies.isEmpty()) {
                scheduledCopies.wait();
              }
              loc = scheduledCopies.remove(0);
            }
            
            try {
              shuffleClientMetrics.threadBusy();
              start(loc);
              size = copyOutput(loc);
              shuffleClientMetrics.successFetch();
            } catch (IOException e) {
              LOG.warn(reduceTask.getTaskID() + " copy failed: " +
                       loc.getTaskAttemptId() + " from " + loc.getHost());
              LOG.warn(StringUtils.stringifyException(e));
              shuffleClientMetrics.failedFetch();
              
              // Reset 
              size = -1;

              // Identify the error type 
              if (e.getClass() == ConnTimeoutException.class) {
                errorType = CopyOutputErrorType.CONNECTION_ERROR;
              }
              else if (e.getClass() == ReadTimeoutException.class) {
                errorType = CopyOutputErrorType.READ_ERROR;
              }

            } finally {
              shuffleClientMetrics.threadFree();
              finish(size, errorType);
            }
          } catch (InterruptedException e) { 
            return; // ALL DONE
          } catch (Throwable th) {
            LOG.error("Map output copy failure: " + 
                      StringUtils.stringifyException(th));
          }
        }
      }
      
      /** Copies a a map output from a remote host, via HTTP. 
       * @param currentLocation the map output location to be copied
       * @return the path (fully qualified) of the copied file
       * @throws IOException if there is an error copying the file
       * @throws InterruptedException if the copier should give up
       */
      private long copyOutput(MapOutputLocation loc
                              ) throws IOException, InterruptedException {
        // check if we still need to copy the output from this location
        if (copiedMapOutputs.contains(loc.getTaskId()) || 
            obsoleteMapIds.contains(loc.getTaskAttemptId())) {
          return CopyResult.OBSOLETE;
        } 
 
        // a temp filename. If this file gets created in ramfs, we're fine,
        // else, we will check the localFS to find a suitable final location
        // for this path
        TaskAttemptID reduceId = reduceTask.getTaskID();
        Path filename = new Path("/" + TaskTracker.getJobCacheSubdir() +
                                 Path.SEPARATOR + getTaskID().getJobID() +
                                 Path.SEPARATOR + reduceId +
                                 Path.SEPARATOR + "output" + "/map_" +
                                 loc.getTaskId().getId() + ".out");
        
        // Copy the map output to a temp file whose name is unique to this attempt 
        Path tmpMapOutput = new Path(filename+"-"+id);
        
        // Copy the map output
        MapOutput mapOutput = getMapOutput(loc, tmpMapOutput);
        if (mapOutput == null) {
          throw new IOException("Failed to fetch map-output for " + 
                                loc.getTaskAttemptId() + " from " + 
                                loc.getHost());
        }
        
        // The size of the map-output
        long bytes = mapOutput.size;
        
        // lock the ReduceTask while we do the rename
        synchronized (ReduceTask.this) {
          if (copiedMapOutputs.contains(loc.getTaskId())) {
            mapOutput.discard();
            return CopyResult.OBSOLETE;
          }

          // Special case: discard empty map-outputs
          if (bytes == 0) {
            try {
              mapOutput.discard();
            } catch (IOException ioe) {
              LOG.info("Couldn't discard output of " + loc.getTaskId());
            }
            
            // Note that we successfully copied the map-output
            noteCopiedMapOutput(loc.getTaskId());
            
            return bytes;
          }
          
          // Process map-output
          if (mapOutput.inMemory) {
            // Save it in the synchronized list of map-outputs
            mapOutputsFilesInMemory.add(mapOutput);
          } else {
            // Rename the temporary file to the final file; 
            // ensure it is on the same partition
            tmpMapOutput = mapOutput.file;
            filename = new Path(tmpMapOutput.getParent(), filename.getName());
            if (!localFileSys.rename(tmpMapOutput, filename)) {
              localFileSys.delete(tmpMapOutput, true);
              bytes = -1;
              throw new IOException("Failed to rename map output " + 
                  tmpMapOutput + " to " + filename);
            }

            synchronized (mapOutputFilesOnDisk) {        
              addToMapOutputFilesOnDisk(localFileSys.getFileStatus(filename));
            }
          }

          // Note that we successfully copied the map-output
          noteCopiedMapOutput(loc.getTaskId());
        }
        
        return bytes;
      }
      
      /**
       * Save the map taskid whose output we just copied.
       * This function assumes that it has been synchronized on ReduceTask.this.
       * 
       * @param taskId map taskid
       */
      private void noteCopiedMapOutput(TaskID taskId) {
        copiedMapOutputs.add(taskId);
        ramManager.setNumCopiedMapOutputs(numMaps - copiedMapOutputs.size());
      }

      /**
       * Get the map output into a local file (either in the inmemory fs or on the 
       * local fs) from the remote server.
       * We use the file system so that we generate checksum files on the data.
       * @param mapOutputLoc map-output to be fetched
       * @param filename the filename to write the data into
       * @param connectionTimeout number of milliseconds for connection timeout
       * @param readTimeout number of milliseconds for read timeout
       * @return the path of the file that got created
       * @throws IOException when something goes wrong
       */
      private MapOutput getMapOutput(MapOutputLocation mapOutputLoc, 
                                     Path filename)
      throws IOException, InterruptedException {
        // Connect
        URLConnection connection = 
          mapOutputLoc.getOutputLocation().openConnection();
        InputStream input = getInputStream(connection, DEFAULT_READ_TIMEOUT, 
                                           STALLED_COPY_TIMEOUT);

        //We will put a file in memory if it meets certain criteria:
        //1. The size of the (decompressed) file should be less than 25% of 
        //    the total inmem fs
        //2. There is space available in the inmem fs

        long decompressedLength = 
          Long.parseLong(connection.getHeaderField(RAW_MAP_OUTPUT_LENGTH));  
        long compressedLength = 
          Long.parseLong(connection.getHeaderField(MAP_OUTPUT_LENGTH));

        // Check if this map-output can be saved in-memory
        boolean shuffleInMemory = ramManager.canFitInMemory(decompressedLength); 

        // Shuffle
        MapOutput mapOutput = null;
        if (shuffleInMemory) { 
          LOG.info("Shuffling " + decompressedLength + " bytes (" + 
              compressedLength + " raw bytes) " + 
              "into RAM from " + mapOutputLoc.getTaskAttemptId());

          mapOutput = shuffleInMemory(mapOutputLoc, connection, input, (int)decompressedLength);
        } else {
          LOG.info("Shuffling " + decompressedLength + " bytes (" + 
              compressedLength + " raw bytes) " + 
              "into Local-FS from " + mapOutputLoc.getTaskAttemptId());

          mapOutput = shuffleToDisk(mapOutputLoc, input, filename, compressedLength);
        }
            
        return mapOutput;
      }

      /** 
       * The connection establishment is attempted multiple times and is given up 
       * only on the last failure. Instead of connecting with a timeout of 
       * X, we try connecting with a timeout of x < X but multiple times. 
       */
      private InputStream getInputStream(URLConnection connection, 
                                         int connectionTimeout, 
                                         int readTimeout) 
      throws IOException {
        int unit = 0;
        if (connectionTimeout < 0) {
          throw new IOException("Invalid timeout "
                                + "[timeout = " + connectionTimeout + " ms]");
        } else if (connectionTimeout > 0) {
          unit = (UNIT_CONNECT_TIMEOUT > connectionTimeout)
                 ? connectionTimeout
                 : UNIT_CONNECT_TIMEOUT;
        }
        // set the read timeout to the total timeout
        connection.setReadTimeout(readTimeout);
        // set the connect timeout to the unit-connect-timeout
        connection.setConnectTimeout(unit);

        try {
          connection.connect();
        } catch (IOException ioe) {
          throw new ConnTimeoutException("Connection Timed out");
        }

        try {
          return connection.getInputStream();
        } catch (IOException ioe) {
          throw new ReadTimeoutException("Read Timed out");
        }
      }

      private MapOutput shuffleInMemory(MapOutputLocation mapOutputLoc,
                                        URLConnection connection, 
                                        InputStream input,
                                        int mapOutputLength) 
      throws IOException, InterruptedException {
        // Reserve ram for the map-output
        boolean createdNow = ramManager.reserve(mapOutputLength, input);
      
        // Reconnect if we need to
        if (!createdNow) {
          // Reconnect
          try {
            connection = mapOutputLoc.getOutputLocation().openConnection();
            input = getInputStream(connection, DEFAULT_READ_TIMEOUT, 
                STALLED_COPY_TIMEOUT);
          } catch (IOException ioe) {
            LOG.info("Failed reopen connection to fetch map-output from " + 
                     mapOutputLoc.getHost());
            
            // Inform the ram-manager
            ramManager.closeInMemoryFile(mapOutputLength);
            ramManager.unreserve(mapOutputLength);
            
            throw ioe;
          }
        }
      
        // Are map-outputs compressed?
        if (codec != null) {
          decompressor.reset();
          input = codec.createInputStream(input, decompressor);
        }
      
        // Copy map-output into an in-memory buffer
        byte[] shuffleData = new byte[mapOutputLength];
        MapOutput mapOutput = 
          new MapOutput(mapOutputLoc.getTaskId(), 
                        mapOutputLoc.getTaskAttemptId(), shuffleData);
        
        int bytesRead = 0;
        try {
          int n = input.read(shuffleData, 0, shuffleData.length);
          while (n > 0) {
            bytesRead += n;
            shuffleClientMetrics.inputBytes(n);

            // indicate we're making progress
            reporter.progress();
            n = input.read(shuffleData, bytesRead, 
                           (shuffleData.length-bytesRead));
          }

          LOG.info("Read " + bytesRead + " bytes from map-output for " +
                   mapOutputLoc.getTaskAttemptId());

          input.close();
        } catch (IOException ioe) {
          LOG.info("Failed to shuffle from " + mapOutputLoc.getTaskAttemptId(), 
                   ioe);

          // Inform the ram-manager
          ramManager.closeInMemoryFile(mapOutputLength);
          ramManager.unreserve(mapOutputLength);
          
          // Discard the map-output
          try {
            mapOutput.discard();
          } catch (IOException ignored) {
            LOG.info("Failed to discard map-output from " + 
                     mapOutputLoc.getTaskAttemptId(), ignored);
          }
          mapOutput = null;
          
          // Close the streams
          IOUtils.cleanup(LOG, input);

          // Re-throw
          throw ioe;
        }

        // Close the in-memory file
        ramManager.closeInMemoryFile(mapOutputLength);

        // Sanity check
        if (bytesRead != mapOutputLength) {
          // Inform the ram-manager
          ramManager.unreserve(mapOutputLength);
          
          // Discard the map-output
          try {
            mapOutput.discard();
          } catch (IOException ignored) {
            // IGNORED because we are cleaning up
            LOG.info("Failed to discard map-output from " + 
                     mapOutputLoc.getTaskAttemptId(), ignored);
          }
          mapOutput = null;

          throw new IOException("Incomplete map output received for " +
                                mapOutputLoc.getTaskAttemptId() + " from " +
                                mapOutputLoc.getOutputLocation() + " (" + 
                                bytesRead + " instead of " + 
                                mapOutputLength + ")"
          );
        }

        // TODO: Remove this after a 'fix' for HADOOP-3647
        if (mapOutputLength > 0) {
          DataInputBuffer dib = new DataInputBuffer();
          dib.reset(shuffleData, 0, shuffleData.length);
          LOG.info("Rec #1 from " + mapOutputLoc.getTaskAttemptId() + " -> (" + 
                   WritableUtils.readVInt(dib) + ", " + 
                   WritableUtils.readVInt(dib) + ") from " + 
                   mapOutputLoc.getHost());
        }
        
        return mapOutput;
      }
      
      private MapOutput shuffleToDisk(MapOutputLocation mapOutputLoc,
                                      InputStream input,
                                      Path filename,
                                      long mapOutputLength) 
      throws IOException {
        // Find out a suitable location for the output on local-filesystem
        Path localFilename = 
          lDirAlloc.getLocalPathForWrite(filename.toUri().getPath(), 
                                         mapOutputLength, conf);

        MapOutput mapOutput = 
          new MapOutput(mapOutputLoc.getTaskId(), mapOutputLoc.getTaskAttemptId(), 
                        conf, localFileSys.makeQualified(localFilename), 
                        mapOutputLength);


        // Copy data to local-disk
        OutputStream output = null;
        long bytesRead = 0;
        try {
          output = localFileSys.create(localFilename);
          
          byte[] buf = new byte[64 * 1024];
          int n = input.read(buf, 0, buf.length);
          while (n > 0) {
            bytesRead += n;
            shuffleClientMetrics.inputBytes(n);
            output.write(buf, 0, n);

            // indicate we're making progress
            reporter.progress();
            n = input.read(buf, 0, buf.length);
          }

          LOG.info("Read " + bytesRead + " bytes from map-output for " +
              mapOutputLoc.getTaskAttemptId());

          output.close();
          input.close();
        } catch (IOException ioe) {
          LOG.info("Failed to shuffle from " + mapOutputLoc.getTaskAttemptId(), 
                   ioe);

          // Discard the map-output
          try {
            mapOutput.discard();
          } catch (IOException ignored) {
            LOG.info("Failed to discard map-output from " + 
                mapOutputLoc.getTaskAttemptId(), ignored);
          }
          mapOutput = null;

          // Close the streams
          IOUtils.cleanup(LOG, input, output);

          // Re-throw
          throw ioe;
        }

        // Sanity check
        if (bytesRead != mapOutputLength) {
          try {
            mapOutput.discard();
          } catch (Throwable th) {
            // IGNORED because we are cleaning up
            LOG.info("Failed to discard map-output from " + 
                mapOutputLoc.getTaskAttemptId(), th);
          }
          mapOutput = null;

          throw new IOException("Incomplete map output received for " +
                                mapOutputLoc.getTaskAttemptId() + " from " +
                                mapOutputLoc.getOutputLocation() + " (" + 
                                bytesRead + " instead of " + 
                                mapOutputLength + ")"
          );
        }

        return mapOutput;

      }
      
    } // MapOutputCopier
    
    private void configureClasspath(JobConf conf)
      throws IOException {
      
      // get the task and the current classloader which will become the parent
      Task task = ReduceTask.this;
      ClassLoader parent = conf.getClassLoader();   
      
      // get the work directory which holds the elements we are dynamically
      // adding to the classpath
      File workDir = new File(task.getJobFile()).getParentFile();
      ArrayList<URL> urllist = new ArrayList<URL>();
      
      // add the jars and directories to the classpath
      String jar = conf.getJar();
      if (jar != null) {      
        File jobCacheDir = new File(new Path(jar).getParent().toString());

        File[] libs = new File(jobCacheDir, "lib").listFiles();
        if (libs != null) {
          for (int i = 0; i < libs.length; i++) {
            urllist.add(libs[i].toURL());
          }
        }
        urllist.add(new File(jobCacheDir, "classes").toURL());
        urllist.add(jobCacheDir.toURL());
        
      }
      urllist.add(workDir.toURL());
      
      // create a new classloader with the old classloader as its parent
      // then set that classloader as the one used by the current jobconf
      URL[] urls = urllist.toArray(new URL[urllist.size()]);
      URLClassLoader loader = new URLClassLoader(urls, parent);
      conf.setClassLoader(loader);
    }
    
    public ReduceCopier(TaskUmbilicalProtocol umbilical, JobConf conf)
      throws IOException {
      
      configureClasspath(conf);
      this.shuffleClientMetrics = new ShuffleClientMetrics(conf);
      this.umbilical = umbilical;      
      this.reduceTask = ReduceTask.this;

      this.scheduledCopies = new ArrayList<MapOutputLocation>(100);
      this.copyResults = new ArrayList<CopyResult>(100);    
      this.numCopiers = conf.getInt("mapred.reduce.parallel.copies", 5);
      this.maxInFlight = 4 * numCopiers;
      this.maxBackoff = conf.getInt("mapred.reduce.copy.backoff", 300);
      this.combinerClass = conf.getCombinerClass();
      combineCollector = (null != combinerClass)
        ? new CombineOutputCollector(reduceCombineOutputCounter)
        : null;
      
      this.ioSortFactor = conf.getInt("io.sort.factor", 10);
      // the exponential backoff formula
      //    backoff (t) = init * base^(t-1)
      // so for max retries we get
      //    backoff(1) + .... + backoff(max_fetch_retries) ~ max
      // solving which we get
      //    max_fetch_retries ~ log((max * (base - 1) / init) + 1) / log(base)
      // for the default value of max = 300 (5min) we get max_fetch_retries = 6
      // the order is 4,8,16,32,64,128. sum of which is 252 sec = 4.2 min
      
      // optimizing for the base 2
      this.maxFetchRetriesPerMap = getClosestPowerOf2((this.maxBackoff * 1000 
                                                       / BACKOFF_INIT) + 1); 
      this.mergeThreshold = conf.getInt("mapred.inmem.merge.threshold", 1000);

      // Setup the RamManager
      ramManager = new ShuffleRamManager(conf);
      ramfsMergeOutputSize = 
        (long)(MAX_INMEM_FILESYS_USE * ramManager.getMemoryLimit());
      
      localFileSys = FileSystem.getLocal(conf);
      
      // hosts -> next contact time
      this.penaltyBox = new LinkedHashMap<String, Long>();
      
      // hostnames
      this.uniqueHosts = new HashSet<String>();
      
      this.lastPollTime = 0;
      

      // Seed the random number generator with a reasonably globally unique seed
      long randomSeed = System.nanoTime() + 
                        (long)Math.pow(this.reduceTask.getPartition(),
                                       (this.reduceTask.getPartition()%10)
                                      );
      this.random = new Random(randomSeed);
      this.maxMapRuntime = 0;
    }
    
    private boolean busyEnough(int numInFlight) {
      return numInFlight > maxInFlight;
    }
    
    public boolean fetchOutputs() throws IOException {
      //The map for (Hosts, List of MapIds from this Host)
      HashMap<String, List<MapOutputLocation>> mapLocations = 
        new HashMap<String, List<MapOutputLocation>>();
      int totalFailures = 0;
      int            numInFlight = 0, numCopied = 0;
      long           bytesTransferred = 0;
      DecimalFormat  mbpsFormat = new DecimalFormat("0.00");
      final Progress copyPhase = 
        reduceTask.getProgress().phase();
      LocalFSMerger localFSMergerThread = null;
      InMemFSMergeThread inMemFSMergeThread = null;
      
      for (int i = 0; i < numMaps; i++) {
        copyPhase.addPhase();       // add sub-phase per file
      }
      
      copiers = new ArrayList<MapOutputCopier>(numCopiers);
      
      Reporter reporter = getReporter(umbilical);

      // start all the copying threads
      for (int i=0; i < numCopiers; i++) {
        MapOutputCopier copier = new MapOutputCopier(conf, reporter);
        copiers.add(copier);
        copier.start();
      }
      
      //start the on-disk-merge thread
      localFSMergerThread = new LocalFSMerger((LocalFileSystem)localFileSys);
      //start the in memory merger thread
      inMemFSMergeThread = new InMemFSMergeThread();
      localFSMergerThread.start();
      inMemFSMergeThread.start();
      
      // start the clock for bandwidth measurement
      long startTime = System.currentTimeMillis();
      long currentTime = startTime;
      long lastProgressTime = startTime;
      long lastOutputTime = 0;
      IntWritable fromEventId = new IntWritable(0);

      //List of unique hosts containing map outputs
      List<String> hostList = new ArrayList<String>();
      
        // loop until we get all required outputs
        while (copiedMapOutputs.size() < numMaps && mergeThrowable == null) {
          
          currentTime = System.currentTimeMillis();
          boolean logNow = false;
          if (currentTime - lastOutputTime > MIN_LOG_TIME) {
            lastOutputTime = currentTime;
            logNow = true;
          }
          if (logNow) {
            LOG.info(reduceTask.getTaskID() + " Need another " 
                   + (numMaps - copiedMapOutputs.size()) + " map output(s) "
                   + "where " + numInFlight + " is already in progress");
          }
          
          try {
            // Put the hash entries for the failed fetches.
            Iterator<MapOutputLocation> locItr = retryFetches.iterator();
            while (locItr.hasNext()) {
              MapOutputLocation loc = locItr.next(); 
              List<MapOutputLocation> locList = 
                mapLocations.get(loc.getHost());
              if (locList == null) {
                locList = new LinkedList<MapOutputLocation>();
                mapLocations.put(loc.getHost(), locList);
                hostList.add(loc.getHost());
              }
              //Add to the beginning of the list so that this map is 
              //tried again before the others and we can hasten the 
              //re-execution of this map should there be a problem
              locList.add(0, loc);
            }
             
            // The call getMapCompletionEvents will update fromEventId to
            // used for the next call to getMapCompletionEvents

            int currentNumObsoleteMapIds = obsoleteMapIds.size();

            int numNewOutputs = getMapCompletionEvents(fromEventId, 
                                                       mapLocations, 
                                                       hostList);

            if (numNewOutputs > 0 || logNow) {
              LOG.info(reduceTask.getTaskID() + ": " +  
                  "Got " + numNewOutputs + 
                  " new map-outputs"); 
            }
            
            int numNewObsoleteMaps = obsoleteMapIds.size()-currentNumObsoleteMapIds;

            if (numNewObsoleteMaps > 0) {
              LOG.info(reduceTask.getTaskID() + ": " +  
                  "Got " + numNewObsoleteMaps + 
                  " obsolete map-outputs from tasktracker ");  
            }
            
            if (retryFetches.size() > 0) {
              LOG.info(reduceTask.getTaskID() + ": " +  
                  "Got " + retryFetches.size() +
                  " map-outputs from previous failures");
            }
            // clear the "failed" fetches hashmap
            retryFetches.clear();
          }
          catch (IOException ie) {
            LOG.warn(reduceTask.getTaskID() +
                    " Problem locating map outputs: " +
                    StringUtils.stringifyException(ie));
          }
          
          // now walk through the cache and schedule what we can
          int numScheduled = 0;
          int numDups = 0;
          
          synchronized (scheduledCopies) {

            // Randomize the map output locations to prevent 
            // all reduce-tasks swamping the same tasktracker
            Collections.shuffle(hostList, this.random);
            
            Iterator<String> hostsItr = hostList.iterator();
            while (hostsItr.hasNext()) {
            
              String host = hostsItr.next();

              List<MapOutputLocation> knownOutputsByLoc = 
                mapLocations.get(host);

              //Identify duplicate hosts here
              if (uniqueHosts.contains(host)) {
                 numDups += knownOutputsByLoc.size() -1; 
                 continue;
              }

              Long penaltyEnd = penaltyBox.get(host);
              boolean penalized = false;
            
              if (penaltyEnd != null) {
                if (currentTime < penaltyEnd.longValue()) {
                  penalized = true;
                } else {
                  penaltyBox.remove(host);
                }
              }
              
              if (penalized)
                continue;

              Iterator<MapOutputLocation> locItr = 
                knownOutputsByLoc.iterator();
            
              while (locItr.hasNext()) {
              
                MapOutputLocation loc = locItr.next();
              
                // Do not schedule fetches from OBSOLETE maps
                if (obsoleteMapIds.contains(loc.getTaskAttemptId())) {
                  locItr.remove();
                  continue;
                }

                uniqueHosts.add(host);
                scheduledCopies.add(loc);
                locItr.remove();  // remove from knownOutputs
                numInFlight++; numScheduled++;

                break; //we have a map from this host
              }
   	
              if (knownOutputsByLoc.size() == 0) {
                mapLocations.remove(host);
                hostsItr.remove();
              }
            }
            scheduledCopies.notifyAll();
          }
          if (numScheduled > 0 || logNow) {
            LOG.info(reduceTask.getTaskID() + " Scheduled " + numScheduled +
                   " outputs (" + penaltyBox.size() +
                   " slow hosts and" + numDups + " dup hosts)");
          }

          if (penaltyBox.size() > 0 && logNow) {
            LOG.info("Penalized(slow) Hosts: ");
            for (String host : penaltyBox.keySet()) {
              LOG.info(host + " Will be considered after: " + 
                  ((penaltyBox.get(host) - currentTime)/1000) + " seconds.");
            }
          }
          
          // if we have no copies in flight and we can't schedule anything
          // new, just wait for a bit
          try {
            if (numInFlight == 0 && numScheduled == 0) {
              // we should indicate progress as we don't want TT to think
              // we're stuck and kill us
              reporter.progress();
              Thread.sleep(5000);
            }
          } catch (InterruptedException e) { } // IGNORE
          
          while (numInFlight > 0 && mergeThrowable == null) {
            LOG.debug(reduceTask.getTaskID() + " numInFlight = " + 
                      numInFlight);
            //the call to getCopyResult will either 
            //1) return immediately with a null or a valid CopyResult object,
            //                 or
            //2) if the numInFlight is above maxInFlight, return with a 
            //   CopyResult object after getting a notification from a 
            //   fetcher thread, 
            //So, when getCopyResult returns null, we can be sure that
            //we aren't busy enough and we should go and get more mapcompletion
            //events from the tasktracker
            CopyResult cr = getCopyResult(numInFlight);

            if (cr == null) {
              break;
            }
            
            if (cr.getSuccess()) {  // a successful copy
              numCopied++;
              lastProgressTime = System.currentTimeMillis();
              bytesTransferred += cr.getSize();
                
              long secsSinceStart = 
                (System.currentTimeMillis()-startTime)/1000+1;
              float mbs = ((float)bytesTransferred)/(1024*1024);
              float transferRate = mbs/secsSinceStart;
                
              copyPhase.startNextPhase();
              copyPhase.setStatus("copy (" + numCopied + " of " + numMaps 
                                  + " at " +
                                  mbpsFormat.format(transferRate) +  " MB/s)");
                
              // Note successfull fetch for this mapId to invalidate
              // (possibly) old fetch-failures
              fetchFailedMaps.remove(cr.getLocation().getTaskId());
            } else if (cr.isObsolete()) {
              //ignore
              LOG.info(reduceTask.getTaskID() + 
                       " Ignoring obsolete copy result for Map Task: " + 
                       cr.getLocation().getTaskAttemptId() + " from host: " + 
                       cr.getHost());
            } else {
              retryFetches.add(cr.getLocation());

              CopyOutputErrorType errorType = cr.getErrorType();
              
              // note the failed-fetch
              TaskAttemptID mapTaskId = cr.getLocation().getTaskAttemptId();
              TaskID mapId = cr.getLocation().getTaskId();
              
              totalFailures++;

              Integer noFailedFetches = 0;

              Integer noReadFailedFetches = 
                mapTaskToReadFailedFetchesMap.get(mapTaskId);

              if (noReadFailedFetches == null) noReadFailedFetches = 0;

              Integer noConnectFailedFetches = 
                mapTaskToConnectFailedFetchesMap.get(mapTaskId);

              if (noConnectFailedFetches == null) noConnectFailedFetches = 0;

              if (errorType == CopyOutputErrorType.READ_ERROR) {
                noReadFailedFetches ++;
                mapTaskToReadFailedFetchesMap.put (mapTaskId, 
                                                   noReadFailedFetches);
                synchronized (ReduceTask.this) {
                  taskStatus.addFetchFailedMap(mapTaskId);
                  LOG.info("Failed to fetch map-output from " + mapTaskId + 
                           " Got a Read Time out," + 
                           " reporting to the JobTracker");
                }
              } else if (errorType == CopyOutputErrorType.CONNECTION_ERROR) {
                noConnectFailedFetches ++;
                mapTaskToConnectFailedFetchesMap.put (
                  mapTaskId, noConnectFailedFetches);

                LOG.info("Task " + getTaskID() + ": Failed fetch #"  
                  + noConnectFailedFetches + " from " + mapTaskId); 

                if ((noConnectFailedFetches >= maxFetchRetriesPerMap) &&              
                   ((noConnectFailedFetches - maxFetchRetriesPerMap) % 2)      
                    == 0) {               
                  synchronized (ReduceTask.this) {       
                    taskStatus.addFetchFailedMap(mapTaskId);
                    LOG.info("Failed to fetch map-output from " + mapTaskId     
                             + " even after MAX_FETCH_RETRIES_PER_MAP"
                             + " (connect) retries... "     
                             + " reporting to the JobTracker");            
                  }
                }
              }

              noFailedFetches = noConnectFailedFetches + 
                                noReadFailedFetches;

              // note unique failed-fetch maps
              if (noFailedFetches == maxFetchRetriesPerMap) {
                fetchFailedMaps.add(mapId);
                  
                // did we have too many unique failed-fetch maps?
                // and did we fail on too many fetch attempts?
                // and did we progress enough
                //     or did we wait for too long without any progress?
               
                // check if the reducer is healthy
                boolean reducerHealthy = 
                    (((float)totalFailures / (totalFailures + numCopied)) 
                     < MAX_ALLOWED_FAILED_FETCH_ATTEMPT_PERCENT);
                
                // check if the reducer has progressed enough
                boolean reducerProgressedEnough = 
                    (((float)numCopied / numMaps) 
                     >= MIN_REQUIRED_PROGRESS_PERCENT);
                
                // check if the reducer is stalled for a long time
                // duration for which the reducer is stalled
                int stallDuration = 
                    (int)(System.currentTimeMillis() - lastProgressTime);
                // duration for which the reducer ran with progress
                int shuffleProgressDuration = 
                    (int)(lastProgressTime - startTime);
                // min time the reducer should run without getting killed
                int minShuffleRunDuration = 
                    (shuffleProgressDuration > maxMapRuntime) 
                    ? shuffleProgressDuration 
                    : maxMapRuntime;
                boolean reducerStalled = 
                    (((float)stallDuration / minShuffleRunDuration) 
                     >= MAX_ALLOWED_STALL_TIME_PERCENT);
                
                // kill if not healthy and has insufficient progress
                if ((fetchFailedMaps.size() >= MAX_FAILED_UNIQUE_FETCHES)
                    && !reducerHealthy 
                    && (!reducerProgressedEnough || reducerStalled)) { 
                  LOG.fatal("Shuffle failed with too many fetch failures " + 
                            "and insufficient progress!" +
                            "Killing task " + getTaskID() + ".");
                  umbilical.shuffleError(getTaskID(), 
                                         "Exceeded MAX_FAILED_UNIQUE_FETCHES;"
                                         + " bailing-out.");
                }
              }
                
              if (errorType == CopyOutputErrorType.CONNECTION_ERROR) {
                // back off exponentially until num_retries <= max_retries
                // back off by max_backoff/2 on subsequent failed attempts
                currentTime = System.currentTimeMillis();
                int currentBackOff = noFailedFetches <= maxFetchRetriesPerMap 
                                   ? BACKOFF_INIT 
                                     * (1 << (noFailedFetches - 1)) 
                                   : (this.maxBackoff * 1000 / 2);
                penaltyBox.put(cr.getHost(), currentTime + currentBackOff);
                LOG.warn(reduceTask.getTaskID() + " adding host " +
                       cr.getHost() + " to penalty box, next contact in " +
                       (currentBackOff/1000) + " seconds");
              } else if (errorType == CopyOutputErrorType.READ_ERROR) {
                int backOff = Math.max(maxMapRuntime/2,
                                    (this.maxBackoff * 1000));    
                penaltyBox.put(cr.getHost(), currentTime + backOff);
                LOG.warn(reduceTask.getTaskID() + " adding host " +
                       cr.getHost() + " to penalty box, next contact in " + 
                       (backOff/1000) + " seconds");  
              }  

            } // Fetch Failure
            uniqueHosts.remove(cr.getHost());
            numInFlight--;
          } // while (numInFlight > 0)
        } // while (copiedMaps < numMaps)
        
        // all done, inform the copiers to exit
        synchronized (copiers) {
          synchronized (scheduledCopies) {
            for (MapOutputCopier copier : copiers) {
              copier.interrupt();
            }
            copiers.clear();
          }
        }
        
        // copiers are done, exit and notify the waiting merge threads
        synchronized (mapOutputFilesOnDisk) {
          exitLocalFSMerge = true;
          mapOutputFilesOnDisk.notify();
        }
        
        ramManager.close();
        
        //Do a merge of in-memory files (if there are any)
        if (mergeThrowable == null) {
          try {
            // Wait for the on-disk merge to complete
            localFSMergerThread.join();
            LOG.info("Interleaved on-disk merge complete: " + 
                     mapOutputFilesOnDisk.size() + " files left.");
            
            //wait for an ongoing merge (if it is in flight) to complete
            inMemFSMergeThread.join();
            LOG.info("In-memory merge complete: " + 
                     mapOutputsFilesInMemory.size() + " files left.");
            } catch (Throwable t) {
            LOG.warn(reduceTask.getTaskID() +
                     " Final merge of the inmemory files threw an exception: " + 
                     StringUtils.stringifyException(t));
            // check if the last merge generated an error
            if (mergeThrowable != null) {
              mergeThrowable = t;
            }
            return false;
          }
        }
        return mergeThrowable == null && copiedMapOutputs.size() == numMaps;
    }
    
    private List<Segment<K, V>> createInMemorySegments() {
      List<Segment<K, V>> inMemorySegments = 
        new LinkedList<Segment<K, V>>();
      synchronized (mapOutputsFilesInMemory) {
        while(mapOutputsFilesInMemory.size() > 0) {
          MapOutput mo = mapOutputsFilesInMemory.remove(0);
          
          Reader<K, V> reader = 
            new InMemoryReader<K, V>(ramManager, mo.mapAttemptId,
                                     mo.data, 0, mo.data.length);
          Segment<K, V> segment = 
            new Segment<K, V>(reader, true);
          inMemorySegments.add(segment);
        }
      }
      return inMemorySegments;
    }
    
    private CopyResult getCopyResult(int numInFlight) {  
      synchronized (copyResults) {
        while (copyResults.isEmpty()) {
          try {
            //The idea is that if we have scheduled enough, we can wait until
            //we hear from one of the copiers.
            if (busyEnough(numInFlight)) {
              copyResults.wait();
            } else {
              return null;
            }
          } catch (InterruptedException e) { }
        }
        return copyResults.remove(0);
      }    
    }
    
    private void addToMapOutputFilesOnDisk(FileStatus status) {
      synchronized (mapOutputFilesOnDisk) {
        mapOutputFilesOnDisk.add(status);
        mapOutputFilesOnDisk.notify();
      }
    }
    
    /** 
     * Queries the {@link TaskTracker} for a set of map-completion events from
     * a given event ID.
     * 
     * @param fromEventId the first event ID we want to start from, this is
     *                     modified by the call to this method
     * @param mapLocations the hash map of map locations by host
     * @param hostsList    the list that contains unique hosts having 
     *                     map outputs, will be updated on the return 
     *                     of this method
     * @return the number of new map-completion events from the given event ID 
     * @throws IOException
     */  
    private int getMapCompletionEvents(IntWritable fromEventId, 
                        HashMap<String,List<MapOutputLocation>> mapLocations,
                        List<String> hostsList)
    throws IOException {
      
      long currentTime = System.currentTimeMillis();    
      long pollTime = lastPollTime + MIN_POLL_INTERVAL;
      int numNewMaps = 0;
      while (currentTime < pollTime) {
        try {
          Thread.sleep(pollTime-currentTime);
        } catch (InterruptedException ie) { } // IGNORE
        currentTime = System.currentTimeMillis();
      }
      
      TaskCompletionEvent events[] = 
        umbilical.getMapCompletionEvents(reduceTask.getJobID(), 
                                       fromEventId.get(), MAX_EVENTS_TO_FETCH);
      
      // Note the last successful poll time-stamp
      lastPollTime = currentTime;

      // Update the last seen event ID
      fromEventId.set(fromEventId.get() + events.length);
      
      // Process the TaskCompletionEvents:
      // 1. Save the SUCCEEDED maps in knownOutputs to fetch the outputs.
      // 2. Save the OBSOLETE/FAILED/KILLED maps in obsoleteOutputs to stop fetching
      //    from those maps.
      // 3. Remove TIPFAILED maps from neededOutputs since we don't need their
      //    outputs at all.
      for (TaskCompletionEvent event : events) {
        switch (event.getTaskStatus()) {
          case SUCCEEDED:
          {
            URI u = URI.create(event.getTaskTrackerHttp());
            String host = u.getHost();
            TaskAttemptID taskId = event.getTaskAttemptId();
            int duration = event.getTaskRunTime();
            if (duration > maxMapRuntime) {
              maxMapRuntime = duration; 
              // adjust max-fetch-retries based on max-map-run-time
              maxFetchRetriesPerMap = 
                  getClosestPowerOf2((maxMapRuntime / BACKOFF_INIT) + 1);
            }
            URL mapOutputLocation = new URL(event.getTaskTrackerHttp() + 
                                    "/mapOutput?job=" + taskId.getJobID() +
                                    "&map=" + taskId + 
                                    "&reduce=" + getPartition());
            List<MapOutputLocation> loc = mapLocations.get(host);
            if (loc == null) {
              loc = new LinkedList<MapOutputLocation>();
              mapLocations.put(host, loc);
              hostsList.add(host);
             }
            loc.add(new MapOutputLocation(taskId, host, mapOutputLocation));
            numNewMaps ++;
          }
          break;
          case FAILED:
          case KILLED:
          case OBSOLETE:
          {
            obsoleteMapIds.add(event.getTaskAttemptId());
            LOG.info("Ignoring obsolete output of " + event.getTaskStatus() + 
                     " map-task: '" + event.getTaskAttemptId() + "'");
          }
          break;
          case TIPFAILED:
          {
            copiedMapOutputs.add(event.getTaskAttemptId().getTaskID());
            LOG.info("Ignoring output of failed map TIP: '" +  
            		 event.getTaskAttemptId() + "'");
          }
          break;
        }
      }
      return numNewMaps;
    }
    
    
    /** Starts merging the local copy (on disk) of the map's output so that
     * most of the reducer's input is sorted i.e overlapping shuffle
     * and merge phases.
     */
    private class LocalFSMerger extends Thread {
      private LocalFileSystem localFileSys;

      public LocalFSMerger(LocalFileSystem fs) {
        this.localFileSys = fs;
        setName("Thread for merging on-disk files");
        setDaemon(true);
      }

      @SuppressWarnings("unchecked")
      public void run() {
        try {
          LOG.info(reduceTask.getTaskID() + " Thread started: " + getName());
          while(!exitLocalFSMerge){
            synchronized (mapOutputFilesOnDisk) {
              while (!exitLocalFSMerge &&
                  mapOutputFilesOnDisk.size() < (2 * ioSortFactor - 1)) {
                LOG.info(reduceTask.getTaskID() + " Thread waiting: " + getName());
                mapOutputFilesOnDisk.wait();
              }
            }
            if(exitLocalFSMerge) {//to avoid running one extra time in the end
              break;
            }
            List<Path> mapFiles = new ArrayList<Path>();
            long approxOutputSize = 0;
            int bytesPerSum = 
              reduceTask.getConf().getInt("io.bytes.per.checksum", 512);
            LOG.info(reduceTask.getTaskID() + "We have  " + 
                mapOutputFilesOnDisk.size() + " map outputs on disk. " +
                "Triggering merge of " + ioSortFactor + " files");
            // 1. Prepare the list of files to be merged. This list is prepared
            // using a list of map output files on disk. Currently we merge
            // io.sort.factor files into 1.
            synchronized (mapOutputFilesOnDisk) {
              for (int i = 0; i < ioSortFactor; ++i) {
                FileStatus filestatus = mapOutputFilesOnDisk.first();
                mapOutputFilesOnDisk.remove(filestatus);
                mapFiles.add(filestatus.getPath());
                approxOutputSize += filestatus.getLen();
              }
            }
            
            // sanity check
            if (mapFiles.size() == 0) {
                return;
            }
            
            // add the checksum length
            approxOutputSize += ChecksumFileSystem
                                .getChecksumLength(approxOutputSize,
                                                   bytesPerSum);
  
            // 2. Start the on-disk merge process
            Path outputPath = 
              lDirAlloc.getLocalPathForWrite(mapFiles.get(0).toString(), 
                                             approxOutputSize, conf)
              .suffix(".merged");
            Writer writer = 
              new Writer(conf, localFileSys, outputPath, 
                         conf.getMapOutputKeyClass(), 
                         conf.getMapOutputValueClass(),
                         codec);
            RawKeyValueIterator iter  = null;
            Path tmpDir = new Path(reduceTask.getTaskID().toString());
            final Reporter reporter = getReporter(umbilical);
            try {
              iter = Merger.merge(conf, localFileSys,
                                  conf.getMapOutputKeyClass(),
                                  conf.getMapOutputValueClass(),
                                  codec, mapFiles.toArray(new Path[mapFiles.size()]), 
                                  true, ioSortFactor, tmpDir, 
                                  conf.getOutputKeyComparator(), reporter);
              
              Merger.writeFile(iter, writer, reporter);
              writer.close();
            } catch (Exception e) {
              localFileSys.delete(outputPath, true);
              throw new IOException (StringUtils.stringifyException(e));
            }
            
            synchronized (mapOutputFilesOnDisk) {
              addToMapOutputFilesOnDisk(localFileSys.getFileStatus(outputPath));
            }
            
            LOG.info(reduceTask.getTaskID() +
                     " Finished merging " + mapFiles.size() + 
                     " map output files on disk of total-size " + 
                     approxOutputSize + "." + 
                     " Local output file is " + outputPath + " of size " +
                     localFileSys.getFileStatus(outputPath).getLen());
            }
        } catch (Throwable t) {
          LOG.warn(reduceTask.getTaskID()
                   + " Merging of the local FS files threw an exception: "
                   + StringUtils.stringifyException(t));
          if (mergeThrowable == null) {
            mergeThrowable = t;
          }
        } 
      }
    }

    private class InMemFSMergeThread extends Thread {
     
      public InMemFSMergeThread() {
        setName("Thread for merging in memory files");
        setDaemon(true);
      }
      
      public void run() {
        LOG.info(reduceTask.getTaskID() + " Thread started: " + getName());
        try {
          boolean exit = false;
          do {
            exit = ramManager.waitForDataToMerge();
            doInMemMerge();
          } while (!exit);
        } catch (Throwable t) {
          LOG.warn(reduceTask.getTaskID() +
                   " Merge of the inmemory files threw an exception: "
                   + StringUtils.stringifyException(t));
          ReduceCopier.this.mergeThrowable = t;
        }
      }
      
      @SuppressWarnings("unchecked")
      private void doInMemMerge() throws IOException{
        if (mapOutputsFilesInMemory.size() == 0) {
          return;
        }
        
        //name this output file same as the name of the first file that is 
        //there in the current list of inmem files (this is guaranteed to
        //be absent on the disk currently. So we don't overwrite a prev. 
        //created spill). Also we need to create the output file now since
        //it is not guaranteed that this file will be present after merge
        //is called (we delete empty files as soon as we see them
        //in the merge method)

        //figure out the mapId 
        TaskID mapId = mapOutputsFilesInMemory.get(0).mapId;
        
        Path outputPath = mapOutputFile.getInputFileForWrite(mapId, 
                          reduceTask.getTaskID(), ramfsMergeOutputSize);

        Writer writer = 
          new Writer(conf, localFileSys, outputPath,
                     conf.getMapOutputKeyClass(),
                     conf.getMapOutputValueClass(),
                     codec);
        
        List<Segment<K, V>> inMemorySegments = createInMemorySegments();
        int noInMemorySegments = inMemorySegments.size();
        
        RawKeyValueIterator rIter = null;
        final Reporter reporter = getReporter(umbilical);
        try {
          LOG.info("Initiating in-memory merge with " + noInMemorySegments + 
                   " segments...");
          
          rIter = Merger.merge(conf, localFileSys,
                               (Class<K>)conf.getMapOutputKeyClass(),
                               (Class<V>)conf.getMapOutputValueClass(),
                               inMemorySegments, inMemorySegments.size(),
                               new Path(reduceTask.getTaskID().toString()),
                               conf.getOutputKeyComparator(), reporter);
          
          if (null == combinerClass) {
            Merger.writeFile(rIter, writer, reporter);
          } else {
            combineCollector.setWriter(writer);
            combineAndSpill(rIter, reduceCombineInputCounter);
          }
          writer.close();

          LOG.info(reduceTask.getTaskID() + 
              " Merge of the " + noInMemorySegments +
              " files in-memory complete." +
              " Local file is " + outputPath + " of size " + 
              localFileSys.getFileStatus(outputPath).getLen());
        } catch (Exception e) { 
          //make sure that we delete the ondisk file that we created 
          //earlier when we invoked cloneFileAttributes
          localFileSys.delete(outputPath, true);
          throw (IOException)new IOException
                  ("Intermedate merge failed").initCause(e);
        }

        // Note the output of the merge
        FileStatus status = localFileSys.getFileStatus(outputPath);
        synchronized (mapOutputFilesOnDisk) {
          addToMapOutputFilesOnDisk(status);
        }
      }
    }

    @SuppressWarnings("unchecked")
    private void combineAndSpill(
        RawKeyValueIterator kvIter,
        Counters.Counter inCounter) throws IOException {
      JobConf job = (JobConf)getConf();
      Reducer combiner = ReflectionUtils.newInstance(combinerClass, job);
      Class keyClass = job.getMapOutputKeyClass();
      Class valClass = job.getMapOutputValueClass();
      RawComparator comparator = job.getOutputKeyComparator();
      try {
        CombineValuesIterator values = new CombineValuesIterator(
            kvIter, comparator, keyClass, valClass, job, Reporter.NULL,
            inCounter);
        while (values.more()) {
          combiner.reduce(values.getKey(), values, combineCollector,
              Reporter.NULL);
          values.nextKey();
        }
      } finally {
        combiner.close();
      }
    }

  }

  /**
   * Return the exponent of the power of two closest to the given
   * positive value, or zero if value leq 0.
   * This follows the observation that the msb of a given value is
   * also the closest power of two, unless the bit following it is
   * set.
   */
  private static int getClosestPowerOf2(int value) {
    if (value <= 0)
      throw new IllegalArgumentException("Undefined for " + value);
    final int hob = Integer.highestOneBit(value);
    return Integer.numberOfTrailingZeros(hob) +
      (((hob >>> 1) & value) == 0 ? 0 : 1);
  }
}
