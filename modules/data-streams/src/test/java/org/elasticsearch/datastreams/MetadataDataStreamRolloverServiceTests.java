/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.datastreams;

import org.elasticsearch.Version;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.rollover.Condition;
import org.elasticsearch.action.admin.indices.rollover.MaxDocsCondition;
import org.elasticsearch.action.admin.indices.rollover.MetadataRolloverService;
import org.elasticsearch.action.admin.indices.rollover.RolloverInfo;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.ComposableIndexTemplate;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.cluster.metadata.DataStreamTestHelper;
import org.elasticsearch.cluster.metadata.IndexAbstraction;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.Template;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexMode;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.elasticsearch.cluster.metadata.IndexMetadata.SETTING_INDEX_UUID;
import static org.elasticsearch.datastreams.DataStreamIndexSettingsProvider.FORMATTER;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

public class MetadataDataStreamRolloverServiceTests extends ESTestCase {

    public void testRolloverClusterStateForDataStream() throws Exception {
        Instant now = Instant.now();
        String dataStreamName = "logs-my-app";
        final DataStream dataStream = new DataStream(
            dataStreamName,
            new DataStream.TimestampField("@timestamp"),
            List.of(new Index(DataStream.getDefaultBackingIndexName(dataStreamName, 1, now.toEpochMilli()), "uuid")),
            1,
            null,
            false,
            false,
            false,
            false,
            IndexMode.TIME_SERIES
        );
        ComposableIndexTemplate template = new ComposableIndexTemplate.Builder().indexPatterns(List.of(dataStream.getName() + "*"))
            .template(new Template(Settings.builder().put("index.mode", "time_series").build(), null, null))
            .dataStreamTemplate(new ComposableIndexTemplate.DataStreamTemplate(false, false, IndexMode.TIME_SERIES))
            .build();
        Metadata.Builder builder = Metadata.builder();
        builder.put("template", template);
        builder.put(
            IndexMetadata.builder(dataStream.getWriteIndex().getName())
                .settings(
                    ESTestCase.settings(Version.CURRENT)
                        .put("index.hidden", true)
                        .put(SETTING_INDEX_UUID, dataStream.getWriteIndex().getUUID())
                        .put("index.mode", "time_series")
                        .put("index.time_series.start_time", FORMATTER.format(now.minus(4, ChronoUnit.HOURS)))
                        .put("index.time_series.end_time", FORMATTER.format(now.minus(2, ChronoUnit.HOURS)))
                )
                .numberOfShards(1)
                .numberOfReplicas(0)
        );
        builder.put(dataStream);
        final ClusterState clusterState = ClusterState.builder(new ClusterName("test")).metadata(builder).build();

        ThreadPool testThreadPool = new TestThreadPool(getTestName());
        try {
            MetadataRolloverService rolloverService = DataStreamTestHelper.getMetadataRolloverService(
                dataStream,
                testThreadPool,
                Set.of(new DataStreamIndexSettingsProvider()),
                xContentRegistry()
            );
            MaxDocsCondition condition = new MaxDocsCondition(randomNonNegativeLong());
            List<Condition<?>> metConditions = Collections.singletonList(condition);
            CreateIndexRequest createIndexRequest = new CreateIndexRequest("_na_");

            long before = testThreadPool.absoluteTimeInMillis();
            MetadataRolloverService.RolloverResult rolloverResult = rolloverService.rolloverClusterState(
                clusterState,
                dataStream.getName(),
                null,
                createIndexRequest,
                metConditions,
                now,
                randomBoolean(),
                false
            );
            long after = testThreadPool.absoluteTimeInMillis();

            String sourceIndexName = DataStream.getDefaultBackingIndexName(dataStream.getName(), dataStream.getGeneration());
            String newIndexName = DataStream.getDefaultBackingIndexName(dataStream.getName(), dataStream.getGeneration() + 1);
            assertEquals(sourceIndexName, rolloverResult.sourceIndexName());
            assertEquals(newIndexName, rolloverResult.rolloverIndexName());
            Metadata rolloverMetadata = rolloverResult.clusterState().metadata();
            assertEquals(dataStream.getIndices().size() + 1, rolloverMetadata.indices().size());
            IndexMetadata rolloverIndexMetadata = rolloverMetadata.index(newIndexName);

            IndexAbstraction ds = rolloverMetadata.getIndicesLookup().get(dataStream.getName());
            assertThat(ds.getType(), equalTo(IndexAbstraction.Type.DATA_STREAM));
            assertThat(ds.getIndices(), hasSize(dataStream.getIndices().size() + 1));
            assertThat(ds.getIndices(), hasItem(rolloverMetadata.index(sourceIndexName).getIndex()));
            assertThat(ds.getIndices(), hasItem(rolloverIndexMetadata.getIndex()));
            assertThat(ds.getWriteIndex(), equalTo(rolloverIndexMetadata.getIndex()));

            RolloverInfo info = rolloverMetadata.index(sourceIndexName).getRolloverInfos().get(dataStream.getName());
            assertThat(info.getTime(), lessThanOrEqualTo(after));
            assertThat(info.getTime(), greaterThanOrEqualTo(before));
            assertThat(info.getMetConditions(), hasSize(1));
            assertThat(info.getMetConditions().get(0).value(), equalTo(condition.value()));

            IndexMetadata im = rolloverMetadata.index(rolloverMetadata.dataStreams().get(dataStreamName).getIndices().get(0));
            Instant startTime1 = IndexSettings.TIME_SERIES_START_TIME.get(im.getSettings());
            Instant endTime1 = IndexSettings.TIME_SERIES_END_TIME.get(im.getSettings());
            im = rolloverMetadata.index(rolloverMetadata.dataStreams().get(dataStreamName).getIndices().get(1));
            Instant startTime2 = IndexSettings.TIME_SERIES_START_TIME.get(im.getSettings());
            Instant endTime2 = IndexSettings.TIME_SERIES_END_TIME.get(im.getSettings());
            assertThat(startTime1.isBefore(endTime1), is(true));
            assertThat(endTime1, equalTo(startTime2));
            assertThat(endTime2.isAfter(endTime1), is(true));
        } finally {
            testThreadPool.shutdown();
        }
    }

}
