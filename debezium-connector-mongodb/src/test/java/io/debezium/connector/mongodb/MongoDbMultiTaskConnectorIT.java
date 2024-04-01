/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.mongodb;

import static io.debezium.config.CommonConnectorConfig.TASKS_MAX;
import static io.debezium.junit.EqualityCheck.LESS_THAN;
import static org.fest.assertions.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.debezium.config.Configuration;
import io.debezium.data.Envelope;
import io.debezium.junit.SkipWhenDatabaseVersion;

public class MongoDbMultiTaskConnectorIT extends AbstractMongoConnectorIT {

    private static final String db = "mongdbmultitask";
    private static final String col = "collection1";

    @Before
    public void beforeEach() {
        initializeConnectorTestFramework();

        config = TestHelper.getConfiguration().edit()
                .with(MongoDbConnectorConfig.LOGICAL_NAME, "mongo")
                .with(MongoDbConnectorConfig.DATABASE_INCLUDE_LIST, db)
                .with(MongoDbConnectorConfig.MONGODB_MULTI_TASK_ENABLED, true)
                .with(MongoDbConnectorConfig.CAPTURE_MODE, "change_streams_with_pre_image")
                .build();

        context = new MongoDbTaskContext(config);
        TestHelper.cleanDatabase(primary(), db);
    }

    @After
    public void afterEach() {
        try {
            stopConnector();
        }
        finally {
            if (context != null) {
                context.getConnectionContext().shutdown();
            }
        }
    }

    /**
     * Verifies that the connector doesn't run with an invalid configuration. This does not actually connect to the Mongo server.
     */
    @Test
    public void shouldNotStartWithInvalidConfiguration() {
        config = Configuration.create()
                .with(MongoDbConnectorConfig.LOGICAL_NAME, "mongo")
                .with(MongoDbConnectorConfig.HOSTS, "rs0/1.2.3.4:27017;rs1/2.3.4.5:27017")
                .with(MongoDbConnectorConfig.MONGODB_MULTI_TASK_ENABLED, true)
                .with(MongoDbConnectorConfig.MONGODB_MULTI_TASK_GEN, 0)
                .build();

        // we expect the engine will log at least one error, so preface it ...
        logger.info("Attempting to start the connector with an INVALID configuration, so expected to see the following error in the log: " +
                "The 'mongodb.multi.task.enabled' value true is invalid: multi-task is not supported for multiple replica sets. received 2 replica sets");
        start(MongoDbConnector.class, config, (success, msg, error) -> {
            assertThat(success).isFalse();
            assertThat(error).isNotNull();
        });
        assertConnectorNotRunning();
    }

    /**
     * Verifies that when multitask is enabled and there is only one task, the behavior should be identical to the regular scenario.
     * The task should consume all the events consumed by the connector.
     * @throws Exception
     */
    @Test
    @SkipWhenDatabaseVersion(check = LESS_THAN, major = 6, reason = "Wall Time in Change Stream is officially released in Mongo > 5.3.")
    public void multiTaskModeWithASingleTasksShouldGenerateRecordForAllEvents() throws Exception {
        final int numDocs = 100;
        final int numUpdates = 35;
        config = config.edit()
                .with(TASKS_MAX, 1)
                .with(MongoDbConnectorConfig.MONGODB_MULTI_TASK_GEN, 0)
                .build();
        start(MongoDbConnector.class, config);
        waitForStreamingRunning("mongodb", "mongo");

        // Insert records
        List<ObjectId> docIds = new ArrayList<>(numDocs);
        for (int i = 0; i < numDocs; i++) {
            ObjectId objId = new ObjectId();
            Document obj = new Document("_id", objId);

            insertDocuments(db, col, obj);
            docIds.add(objId);
        }

        // Consume the records of all the inserts and verify that they match the inserted documents
        final SourceRecords inserts = consumeRecordsByTopic(numDocs);
        assertNoRecordsToConsume();
        assertThat(inserts.allRecordsInOrder().size()).isEqualTo(numDocs);
        for (int i = 0; i < numDocs; i++) {
            SourceRecord record = inserts.allRecordsInOrder().get(i);
            assertThat(TestHelper.getDocumentId(record)).isEqualTo(TestHelper.formatObjectId(docIds.get(i)));
        }

        // Update some documents
        List<ObjectId> updatedDocIds = new ArrayList<>(numUpdates);
        for (int i = 0; i < numUpdates; i++) {
            ObjectId id = docIds.get((int) (Math.random() * docIds.size()));
            updatedDocIds.add(id);
            updateDocument(db, col, TestHelper.getFilterFromId(id), new Document("$set", new Document("update", i)));
        }

        // Consume the records of all the updates and verify that they match the updates
        final SourceRecords updates = consumeRecordsByTopic(numUpdates);
        assertNoRecordsToConsume();
        assertThat(updates.allRecordsInOrder().size()).isEqualTo(numUpdates);
        for (int i = 0; i < numUpdates; i++) {
            SourceRecord record = updates.allRecordsInOrder().get(i);
            assertThat(TestHelper.getDocumentId(record)).isEqualTo(TestHelper.formatObjectId(updatedDocIds.get(i)));
            assertThat(((Struct) record.value()).getStruct("updateDescription").getString("updatedFields"))
                    .isEqualTo("{\"update\": " + i + "}");
        }

        // Delete the documents
        for (int i = 0; i < numDocs; i++) {
            deleteDocuments(db, col, TestHelper.getFilterFromId(docIds.get(i)));
        }

        // Consume the records of all the deletes and verify that they match the deleted documents
        final SourceRecords deletes = consumeRecordsByTopic(2 * numDocs);
        assertNoRecordsToConsume();
        assertThat(deletes.allRecordsInOrder().size()).isEqualTo(2 * numDocs);
        for (int i = 0; i < numDocs; i++) {
            SourceRecord record = deletes.allRecordsInOrder().get(2 * i);
            assertThat(TestHelper.getDocumentId(record)).isEqualTo(TestHelper.formatObjectId(docIds.get(i)));
        }
    }

    /**
     * Verifies that each task only consumes the events that they are assigned, which defaults to using the timestamp as the property.
     * Because the IT framework uses EmbeddedEngine, which does not yet support multiple tasks, we have to
     * design the test to only verify the default task. We can update this logic in future once EnbeddedEngine supports multiple tasks.
     * @throws Exception
     */
    @Test
    @SkipWhenDatabaseVersion(check = LESS_THAN, major = 6, reason = "Wall Time in Change Stream is officially released in Mongo > 5.3.")
    public void multiTaskModeWithMultipleTasksShouldGenerateRecordForOnlyAssignedEvents() throws Exception {
        final int numTasks = 4;
        final int numDocs = 100; // numDocs should be a multiple of numTasks to simply the test logic
        final int defaultTaskId = 0;
        config = config.edit()
                .with(TASKS_MAX, numTasks)
                .with(MongoDbConnectorConfig.MONGODB_MULTI_TASK_GEN, 0)
                .build();
        start(MongoDbConnector.class, config);
        waitForStreamingRunning("mongodb", "mongo", defaultTaskId);

        // Insert records
        Set<String> docIdStrs = new HashSet<>();
        List<ObjectId> docIds = new ArrayList<>(numDocs);
        for (int i = 0; i < numDocs; i++) {
            ObjectId objId = new ObjectId();
            Document obj = new Document("_id", objId);

            insertDocuments(db, col, obj);
            docIdStrs.add(TestHelper.formatObjectId(objId));
            docIds.add(objId);
        }

        // Consume the records of all the inserts and verify that they match the inserted documents
        // and that they should be assigned to this task
        final SourceRecords inserts = consumeRecordsByTopic(numDocs);
        assertNoRecordsToConsume();
        assertThat(inserts.allRecordsInOrder().size()).isLessThan(numDocs);
        for (SourceRecord record : inserts.allRecordsInOrder()) {
            final long wallTime = getWallTime(record);
            assertThat(wallTime % numTasks).isEqualTo(defaultTaskId)
                    .overridingErrorMessage("Record with offset wall time " + wallTime + " should not be assigned to task " + defaultTaskId);
            assertThat(TestHelper.getDocumentId(record)).isIn(docIdStrs);
        }

        // updates records
        Set<String> updateDocIdStrs = new HashSet<>();
        List<ObjectId> updatedDocIds = new ArrayList<>(numDocs);
        for (int i = 0; i < numDocs; i++) {
            ObjectId id = docIds.get(i);
            updateDocument(db, col, TestHelper.getFilterFromId(id), new Document("$set", new Document("update", "success")));
            updateDocIdStrs.add(TestHelper.formatObjectId(id));
            updatedDocIds.add(id);
        }

        // Consume the records of all the udpates and verify that they match the inserted documents
        // and that they should be assigned to this task
        final SourceRecords updates = consumeRecordsByTopic(numDocs);
        assertNoRecordsToConsume();
        assertThat(updates.allRecordsInOrder().size()).isLessThan(numDocs);
        for (SourceRecord record : updates.allRecordsInOrder()) {
            final long wallTime = getWallTime(record);
            assertThat(wallTime % numTasks).isEqualTo(defaultTaskId)
                    .overridingErrorMessage("Record with offset wall time " + wallTime + " should not be assigned to task " + defaultTaskId);
            assertThat(TestHelper.getDocumentId(record)).isIn(updateDocIdStrs);
            assertThat(((Struct) record.value()).getStruct("updateDescription").getString("updatedFields"))
                    .isEqualTo("{\"update\": \"success\"}");
        }

        // Delete the documents
        Collections.shuffle(docIds); // shuffle the list so that deletes are not in the same order as inserts
        for (int i = 0; i < numDocs; i++) {
            deleteDocuments(db, col, TestHelper.getFilterFromId(docIds.get(i)));
        }

        // Consume the records of all the deletes and verify that they match the deleted documents
        final SourceRecords deletes = consumeRecordsByTopic(numDocs);
        assertNoRecordsToConsume();
        assertThat(deletes.allRecordsInOrder().size()).isLessThan(numDocs);
        for (SourceRecord record : deletes.allRecordsInOrder()) {
            final long wallTime = getWallTime(record);
            assertThat(wallTime % numTasks).isEqualTo(defaultTaskId)
                    .overridingErrorMessage("Record with offset wall time" + wallTime + " should not be assigned to task " + defaultTaskId);
            assertThat(TestHelper.getDocumentId(record)).isIn(docIdStrs);
        }
    }

    private long getWallTime(SourceRecord record) {
        final Object recordValue = record.value();
        if (recordValue != null && recordValue instanceof Struct) {
            final Struct value = (Struct) recordValue;

            final long wallTime = value.getStruct(Envelope.FieldName.SOURCE).getInt64(SourceInfo.WALL_TIME);
            return wallTime;
        }

        // TODO we need to investigate WALL TIME is not present in some cases.
        // In this case this will always be managed by task 0.
        return 0;
    }
}
