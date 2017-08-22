/*
 * Copyright (c) 2016 Uber Technologies, Inc. (hoodie-dev-group@uber.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uber.hoodie;

import com.uber.hoodie.common.HoodieClientTestUtils;
import com.uber.hoodie.common.HoodieMergeOnReadTestUtils;
import com.uber.hoodie.common.HoodieTestDataGenerator;
import com.uber.hoodie.common.TestRawTripPayload.MetadataMergeWriteStatus;
import com.uber.hoodie.common.minicluster.HdfsTestService;
import com.uber.hoodie.common.model.HoodieDataFile;
import com.uber.hoodie.common.model.HoodieKey;
import com.uber.hoodie.common.model.HoodieRecord;
import com.uber.hoodie.common.model.HoodieTableType;
import com.uber.hoodie.common.model.HoodieTestUtils;
import com.uber.hoodie.common.table.HoodieTableMetaClient;
import com.uber.hoodie.common.table.HoodieTimeline;
import com.uber.hoodie.common.table.TableFileSystemView;
import com.uber.hoodie.common.table.timeline.HoodieInstant;
import com.uber.hoodie.common.table.view.HoodieTableFileSystemView;
import com.uber.hoodie.common.util.FSUtils;
import com.uber.hoodie.config.HoodieCompactionConfig;
import com.uber.hoodie.config.HoodieIndexConfig;
import com.uber.hoodie.config.HoodieStorageConfig;
import com.uber.hoodie.config.HoodieWriteConfig;
import com.uber.hoodie.index.HoodieIndex;
import com.uber.hoodie.io.compact.HoodieCompactor;
import com.uber.hoodie.io.compact.HoodieRealtimeTableCompactor;
import com.uber.hoodie.table.HoodieTable;

import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.SQLContext;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.uber.hoodie.common.HoodieTestDataGenerator.TRIP_EXAMPLE_SCHEMA;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestMergeOnReadTable {
    private transient JavaSparkContext jsc = null;
    private transient SQLContext sqlContext;
    private String basePath = null;
    private HoodieCompactor compactor;
    private FileSystem fs;

    //NOTE : Be careful in using DFS (FileSystem.class) vs LocalFs(RawLocalFileSystem.class)
    //The implementation and gurantees of many API's differ, for example check rename(src,dst)
    private static MiniDFSCluster dfsCluster;
    private static DistributedFileSystem dfs;
    private static HdfsTestService hdfsTestService;

    @AfterClass
    public static void cleanUp() throws Exception {
        if (hdfsTestService != null) {
            hdfsTestService.stop();
            dfsCluster.shutdown();;
        }
        FSUtils.setFs(null);
        // Need to closeAll to clear FileSystem.Cache, required because DFS and LocalFS used in the same JVM
        FileSystem.closeAll();
        HoodieTestUtils.resetFS();
    }

    @BeforeClass
    public static void setUpDFS() throws IOException {
        // Need to closeAll to clear FileSystem.Cache, required because DFS and LocalFS used in the same JVM
        FileSystem.closeAll();
        if (hdfsTestService == null) {
            hdfsTestService = new HdfsTestService();
            dfsCluster = hdfsTestService.start(true);
            // Create a temp folder as the base path
            dfs = dfsCluster.getFileSystem();
        }
        FSUtils.setFs(dfs);
        HoodieTestUtils.resetFS();
    }

    @Before
    public void init() throws IOException {
        this.fs = FSUtils.getFs();

        // Initialize a local spark env
        jsc = new JavaSparkContext(HoodieClientTestUtils.getSparkConfForTest("TestHoodieMergeOnReadTable"));
        jsc.hadoopConfiguration().addResource(FSUtils.getFs().getConf());

        // Create a temp folder as the base path
        TemporaryFolder folder = new TemporaryFolder();
        folder.create();
        basePath = folder.getRoot().getAbsolutePath();
        dfs.mkdirs(new Path(basePath));
        FSUtils.setFs(dfs);
        HoodieTestUtils.initTableType(basePath, HoodieTableType.MERGE_ON_READ);

        compactor = new HoodieRealtimeTableCompactor();

        //SQLContext stuff
        sqlContext = new SQLContext(jsc);
    }

    @After
    public void clean() {
        if (basePath != null) {
            new File(basePath).delete();
        }
        if (jsc != null) {
            jsc.stop();
        }
    }

    @Test
    public void testSimpleInsertAndUpdate() throws Exception {
        HoodieWriteConfig cfg = getConfig();
        HoodieWriteClient client = new HoodieWriteClient(jsc, cfg);

        /**
         * Write 1 (only inserts)
         */
        String newCommitTime = "001";
        HoodieTestDataGenerator dataGen = new HoodieTestDataGenerator();
        List<HoodieRecord> records = dataGen.generateInserts(newCommitTime, 200);
        JavaRDD<HoodieRecord> writeRecords = jsc.parallelize(records, 1);

        List<WriteStatus> statuses = client.upsert(writeRecords, newCommitTime).collect();
        assertNoWriteErrors(statuses);

        HoodieTableMetaClient metaClient = new HoodieTableMetaClient(fs, cfg.getBasePath());
        HoodieTable hoodieTable = HoodieTable.getHoodieTable(metaClient, cfg);

        Optional<HoodieInstant> deltaCommit =
            metaClient.getActiveTimeline().getDeltaCommitTimeline().firstInstant();
        assertTrue(deltaCommit.isPresent());
        assertEquals("Delta commit should be 001", "001", deltaCommit.get().getTimestamp());

        Optional<HoodieInstant> commit =
            metaClient.getActiveTimeline().getCommitTimeline().firstInstant();
        assertFalse(commit.isPresent());

        FileStatus[] allFiles = HoodieTestUtils.listAllDataFilesInPath(metaClient.getFs(), cfg.getBasePath());
        TableFileSystemView.ReadOptimizedView roView = new HoodieTableFileSystemView(metaClient,
                hoodieTable.getCompletedCompactionCommitTimeline(), allFiles);
        Stream<HoodieDataFile> dataFilesToRead = roView.getLatestDataFiles();
        assertTrue(!dataFilesToRead.findAny().isPresent());

        roView = new HoodieTableFileSystemView(metaClient, hoodieTable.getCompletedCommitTimeline(), allFiles);
        dataFilesToRead = roView.getLatestDataFiles();
        assertTrue("RealtimeTableView should list the parquet files we wrote in the delta commit",
            dataFilesToRead.findAny().isPresent());

        /**
         * Write 2 (updates)
         */
        newCommitTime = "004";
        records = dataGen.generateUpdates(newCommitTime, 100);
        Map<HoodieKey, HoodieRecord> recordsMap = new HashMap<>();
        for (HoodieRecord rec : records) {
            if (!recordsMap.containsKey(rec.getKey())) {
                recordsMap.put(rec.getKey(), rec);
            }
        }


        statuses = client.upsert(jsc.parallelize(records, 1), newCommitTime).collect();
        // Verify there are no errors
        assertNoWriteErrors(statuses);
        metaClient = new HoodieTableMetaClient(fs, cfg.getBasePath());
        deltaCommit = metaClient.getActiveTimeline().getDeltaCommitTimeline().lastInstant();
        assertTrue(deltaCommit.isPresent());
        assertEquals("Latest Delta commit should be 004", "004", deltaCommit.get().getTimestamp());

        commit = metaClient.getActiveTimeline().getCommitTimeline().firstInstant();
        assertFalse(commit.isPresent());


        HoodieCompactor compactor = new HoodieRealtimeTableCompactor();
        HoodieTable table = HoodieTable.getHoodieTable(metaClient, getConfig());

        compactor.compact(jsc, getConfig(), table);

        allFiles = HoodieTestUtils.listAllDataFilesInPath(fs, cfg.getBasePath());
        roView = new HoodieTableFileSystemView(metaClient, hoodieTable.getCompletedCommitTimeline(), allFiles);
        dataFilesToRead = roView.getLatestDataFiles();
        assertTrue(dataFilesToRead.findAny().isPresent());

        // verify that there is a commit
        HoodieReadClient readClient = new HoodieReadClient(jsc, basePath, sqlContext);
        assertEquals("Expecting a single commit.", 1, readClient.listCommitsSince("000").size());
        String latestCompactionCommitTime = readClient.latestCommit();
        assertTrue(HoodieTimeline
            .compareTimestamps("000", latestCompactionCommitTime, HoodieTimeline.LESSER));
        assertEquals("Must contain 200 records", 200, readClient.readSince("000").count());
    }

    // Check if record level metadata is aggregated properly at the end of write.
    @Test
    public void testMetadataAggregateFromWriteStatus() throws Exception {
        HoodieWriteConfig cfg = getConfigBuilder().withWriteStatusClass(MetadataMergeWriteStatus.class).build();
        HoodieWriteClient client = new HoodieWriteClient(jsc, cfg);

        String newCommitTime = "001";
        HoodieTestDataGenerator dataGen = new HoodieTestDataGenerator();
        List<HoodieRecord> records = dataGen.generateInserts(newCommitTime, 200);
        JavaRDD<HoodieRecord> writeRecords = jsc.parallelize(records, 1);

        List<WriteStatus> statuses = client.upsert(writeRecords, newCommitTime).collect();
        assertNoWriteErrors(statuses);
        Map<String, String> allWriteStatusMergedMetadataMap = MetadataMergeWriteStatus .mergeMetadataForWriteStatuses(statuses);
        assertTrue(allWriteStatusMergedMetadataMap.containsKey("InputRecordCount_1506582000"));
        // For metadata key InputRecordCount_1506582000, value is 2 for each record. So sum of this should be 2 * records.size()
        assertEquals(String.valueOf(2 * records.size()), allWriteStatusMergedMetadataMap.get("InputRecordCount_1506582000"));
    }

        @Test
    public void testSimpleInsertAndDelete() throws Exception {
        HoodieWriteConfig cfg = getConfig();
        HoodieWriteClient client = new HoodieWriteClient(jsc, cfg);

        /**
         * Write 1 (only inserts, written as parquet file)
         */
        String newCommitTime = "001";
        HoodieTestDataGenerator dataGen = new HoodieTestDataGenerator();
        List<HoodieRecord> records = dataGen.generateInserts(newCommitTime, 20);
        JavaRDD<HoodieRecord> writeRecords = jsc.parallelize(records, 1);

        List<WriteStatus> statuses = client.upsert(writeRecords, newCommitTime).collect();
        assertNoWriteErrors(statuses);

        HoodieTableMetaClient metaClient = new HoodieTableMetaClient(fs, cfg.getBasePath());
        HoodieTable hoodieTable = HoodieTable.getHoodieTable(metaClient, cfg);

        Optional<HoodieInstant> deltaCommit =
                metaClient.getActiveTimeline().getDeltaCommitTimeline().firstInstant();
        assertTrue(deltaCommit.isPresent());
        assertEquals("Delta commit should be 001", "001", deltaCommit.get().getTimestamp());

        Optional<HoodieInstant> commit =
                metaClient.getActiveTimeline().getCommitTimeline().firstInstant();
        assertFalse(commit.isPresent());

        FileStatus[] allFiles = HoodieTestUtils.listAllDataFilesInPath(metaClient.getFs(), cfg.getBasePath());
        TableFileSystemView.ReadOptimizedView roView = new HoodieTableFileSystemView(metaClient,
                hoodieTable.getCompletedCompactionCommitTimeline(), allFiles);
        Stream<HoodieDataFile> dataFilesToRead = roView.getLatestDataFiles();
        assertTrue(!dataFilesToRead.findAny().isPresent());

        roView = new HoodieTableFileSystemView(metaClient, hoodieTable.getCompletedCommitTimeline(), allFiles);
        dataFilesToRead = roView.getLatestDataFiles();
        assertTrue("RealtimeTableView should list the parquet files we wrote in the delta commit",
                dataFilesToRead.findAny().isPresent());

        /**
         * Write 2 (only inserts, written to .log file)
         */
        newCommitTime = "002";
        records = dataGen.generateInserts(newCommitTime, 20);
        writeRecords = jsc.parallelize(records, 1);
        statuses = client.upsert(writeRecords, newCommitTime).collect();
        assertNoWriteErrors(statuses);

        /**
         * Write 2 (only deletes, written to .log file)
         */
        newCommitTime = "004";
        List<HoodieRecord> fewRecordsForDelete = dataGen.generateDeletesFromExistingRecords(records);

        statuses = client.upsert(jsc.parallelize(fewRecordsForDelete, 1), newCommitTime).collect();
        // Verify there are no errors
        assertNoWriteErrors(statuses);

        metaClient = new HoodieTableMetaClient(fs, cfg.getBasePath());
        deltaCommit = metaClient.getActiveTimeline().getDeltaCommitTimeline().lastInstant();
        assertTrue(deltaCommit.isPresent());
        assertEquals("Latest Delta commit should be 004", "004", deltaCommit.get().getTimestamp());

        commit = metaClient.getActiveTimeline().getCommitTimeline().firstInstant();
        assertFalse(commit.isPresent());

        allFiles = HoodieTestUtils.listAllDataFilesInPath(fs, cfg.getBasePath());
        roView = new HoodieTableFileSystemView(metaClient, hoodieTable.getCompletedCommitTimeline(), allFiles);
        dataFilesToRead = roView.getLatestDataFiles();
        assertTrue(dataFilesToRead.findAny().isPresent());

        List<String> dataFiles = roView.getLatestDataFiles().map(hf -> hf.getPath()).collect(Collectors.toList());
        List<GenericRecord> recordsRead = HoodieMergeOnReadTestUtils.getRecordsUsingInputFormat(dataFiles);
        //Wrote 40 records and deleted 20 records, so remaining 40-20 = 20
        assertEquals("Must contain 20 records", 20, recordsRead.size());
    }

    private HoodieWriteConfig getConfig() {
        return getConfigBuilder().build();
    }

    private HoodieWriteConfig.Builder getConfigBuilder() {
        return HoodieWriteConfig.newBuilder().withPath(basePath)
            .withSchema(TRIP_EXAMPLE_SCHEMA).withParallelism(2, 2)
            .withCompactionConfig(
                HoodieCompactionConfig.newBuilder().compactionSmallFileSize(1024 * 1024)
                    .withInlineCompaction(false).build())
            .withStorageConfig(HoodieStorageConfig.newBuilder().limitFileSize(1024 * 1024).build())

            .forTable("test-trip-table").withIndexConfig(
                HoodieIndexConfig.newBuilder().withIndexType(HoodieIndex.IndexType.BLOOM).build());
    }

    private void assertNoWriteErrors(List<WriteStatus> statuses) {
        // Verify there are no errors
        for (WriteStatus status : statuses) {
            assertFalse("Errors found in write of " + status.getFileId(), status.hasErrors());
        }
    }
}
