package org.apache.cassandra.spark;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Uninterruptibles;

import org.apache.cassandra.spark.data.CqlField;
import org.apache.cassandra.spark.data.VersionRunner;
import org.apache.cassandra.spark.data.fourzero.complex.CqlTuple;
import org.apache.cassandra.spark.data.fourzero.complex.CqlUdt;
import org.apache.cassandra.spark.utils.RandomUtils;

import org.apache.commons.lang3.mutable.MutableLong;
import org.junit.Test;

import org.apache.cassandra.spark.reader.CassandraBridge;
import org.apache.cassandra.spark.shaded.fourzero.datastax.driver.core.TupleValue;
import org.apache.cassandra.spark.shaded.fourzero.datastax.driver.core.UDTValue;
import org.apache.spark.sql.Row;
import scala.collection.mutable.WrappedArray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.SourceDSL.characters;
import static org.quicktheories.generators.SourceDSL.integers;

/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

/**
 * End-to-end tests that write random data to multiple SSTables, reads the data back into Spark and verifies the rows in Spark match the expected.
 * Uses QuickTheories to test many combinations of field data types and clustering key sort order.
 * Uses custom SSTableTombstoneWriter to write SSTables with tombstones to verify Spark bulk reader correctly purges tombstoned data.
 */
public class EndToEndTests extends VersionRunner
{

    public EndToEndTests(CassandraBridge.CassandraVersion version)
    {
        super(version);
    }

    /* partition key tests */

    @Test
    public void testSinglePartitionKey()
    {
        Tester.builder(TestSchema.builder().withPartitionKey("pk", bridge.uuid()).withColumn("c1", bridge.bigint()).withColumn("c2", bridge.text()).build())
              .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS)
              .withSumField("c1")
              .run();
    }

    @Test
    public void testOnlyPartitionKeys()
    {
        // special case where schema is only partition keys
        Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.uuid()).build())
              .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS)
              .run();
        Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.uuid()).withPartitionKey("b", bridge.bigint()).build())
              .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS)
              .run();
    }

    @Test
    public void testOnlyPartitionClusteringKeys()
    {
        Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.uuid()).withClusteringKey("b", bridge.bigint()).withClusteringKey("c", bridge.text()).build())
              .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS)
              .run();
    }

    @Test
    public void testMultiplePartitionKeys()
    {
        Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.uuid()).withPartitionKey("b", bridge.bigint()).withColumn("c", bridge.text()).withColumn("d", bridge.bigint()).build())
              .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS)
              .withSumField("d")
              .run();
    }

    /* clustering key tests */

    @Test
    public void testBasicSingleClusteringKey()
    {
        Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.bigint()).withClusteringKey("b", bridge.bigint()).withColumn("c", bridge.bigint()).build())
              .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS)
              .withSumField("c")
              .run();
    }

    @Test
    public void testSingleClusteringKeyOrderBy()
    {
        qt().forAll(TestUtils.cql3Type(bridge), TestUtils.sortOrder())
            .checkAssert((clusteringKeyType, sortOrder) ->
                         Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.bigint()).withClusteringKey("b", clusteringKeyType).withColumn("c", bridge.bigint()).withSortOrder(sortOrder).build())
                               .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS)
                               .run());
    }

    @Test
    public void testMultipleClusteringKeys()
    {
        Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.uuid()).withClusteringKey("b", bridge.aInt()).withClusteringKey("c", bridge.text()).withColumn("d", bridge.text()).withColumn("e", bridge.bigint()).build())
              .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS)
              .withSumField("e")
              .run();
    }

    @Test
    public void testManyClusteringKeys()
    {
        Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.uuid())
                                 .withClusteringKey("b", bridge.timestamp()).withClusteringKey("c", bridge.text()).withClusteringKey("d", bridge.uuid()).withClusteringKey("e", bridge.aFloat())
                                 .withColumn("f", bridge.text()).withColumn("g", bridge.bigint()).build())
              .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS)
              .withSumField("g")
              .run();
    }

    /* data type tests */

    @Test
    public void testAllDataTypesPartitionKey()
    {
        // test partition key can be read for all data types
        qt().forAll(TestUtils.cql3Type(bridge))
            .checkAssert((partitionKeyType) -> {
                // boolean or empty types have limited cardinality
                final int numRows = partitionKeyType.cardinality(10);
                Tester.builder(TestSchema.builder().withPartitionKey("a", partitionKeyType).withColumn("b", bridge.bigint()).build())
                      .withNumRandomSSTables(1)
                      .withNumRandomRows(numRows)
                      .withExpectedRowCountPerSSTable(numRows)
                      .run();
            });
    }

    @Test
    public void testAllDataTypesValueColumn()
    {
        // test value column can be read for all data types
        qt().forAll(TestUtils.cql3Type(bridge))
            .checkAssert((valueType) -> Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.bigint()).withColumn("b", valueType).build())
                                              .withNumRandomSSTables(1)
                                              .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS)
                                              .run());
    }

    /* compaction */

    @Test
    public void testMultipleSSTablesCompaction()
    {
        final AtomicLong startTotal = new AtomicLong(0);
        final AtomicLong newTotal = new AtomicLong(0);
        final Map<UUID, Long> col1 = new HashMap<>(Tester.DEFAULT_NUM_ROWS);
        final Map<UUID, String> col2 = new HashMap<>(Tester.DEFAULT_NUM_ROWS);
        Tester.builder(TestSchema.builder().withPartitionKey("pk", bridge.uuid()).withColumn("c1", bridge.bigint()).withColumn("c2", bridge.text()).build())
              .dontWriteRandomData()
              .withSSTableWriter(writer -> {
                  for (int i = 0; i < Tester.DEFAULT_NUM_ROWS; i++)
                  {
                      final UUID pk = java.util.UUID.randomUUID();
                      final long c1 = RandomUtils.RANDOM.nextInt(10000000);
                      final String c2 = java.util.UUID.randomUUID().toString();
                      startTotal.addAndGet(c1);
                      col1.put(pk, c1);
                      col2.put(pk, c2);
                      writer.write(pk, c1, c2);
                  }
              })
              // overwrite c1 with new value greater than previous
              .withSSTableWriter(writer -> {
                  for (final UUID pk : col1.keySet())
                  {
                      final long newBalance = (long) RandomUtils.RANDOM.nextInt(10000000) + col1.get(pk);
                      assertTrue(newBalance > col1.get(pk));
                      newTotal.addAndGet(newBalance);
                      col1.put(pk, newBalance);
                      writer.write(pk, newBalance, col2.get(pk));
                  }
              })
              .withCheck(ds -> {
                  assertTrue(startTotal.get() < newTotal.get());
                  long sum = 0;
                  int count = 0;
                  for (final Row row : ds.collectAsList())
                  {
                      final UUID pk = java.util.UUID.fromString(row.getString(0));
                      assertEquals(row.getLong(1), col1.get(pk).longValue());
                      assertEquals(row.getString(2), col2.get(pk));
                      sum += (long) row.get(1);
                      count++;
                  }
                  assertEquals(Tester.DEFAULT_NUM_ROWS, count);
                  assertEquals(newTotal.get(), sum);
              })
              .withReset(() -> {
                  startTotal.set(0);
                  newTotal.set(0);
                  col1.clear();
                  col2.clear();
              });
    }

    @Test
    public void testCompaction()
    {
        final int numRowsCols = 20;
        final AtomicInteger total = new AtomicInteger(0);
        Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.aInt()).withClusteringKey("b", bridge.aInt()).withColumn("c", bridge.aInt()).build())
              // don't write random data
              .dontWriteRandomData()
              // write some SSTables deterministically
              .withSSTableWriter((writer) -> {
                  for (int i = 0; i < numRowsCols; i++)
                  {
                      for (int j = 0; j < numRowsCols; j++)
                      {
                          writer.write(i, j, 0);
                      }
                  }
              })
              .withSSTableWriter((writer) -> {
                  for (int i = 0; i < numRowsCols; i++)
                  {
                      for (int j = 0; j < numRowsCols; j++)
                      {
                          writer.write(i, j, 1);
                      }
                  }
              })
              .withSSTableWriter((writer) -> {
                  for (int i = 0; i < numRowsCols; i++)
                  {
                      for (int j = 0; j < numRowsCols; j++)
                      {
                          final int num = j * 500;
                          total.addAndGet(num);
                          writer.write(i, j, num);
                      }
                  }
              })
              .withReadListener(row -> {
                  // we should have compacted the sstables to remove duplicate data and tombstones
                  assert (row.getInteger("b") * 500) == row.getInteger("c");
              })
              // verify sums to correct total
              .withCheck(ds -> assertEquals(total.get(), ds.groupBy().sum("c").first().getLong(0)))
              .withCheck(ds -> assertEquals(numRowsCols * numRowsCols, ds.groupBy().count().first().getLong(0)))
              .withReset(() -> total.set(0))
              .run();
    }

    @Test
    public void testSingleClusteringKey()
    {
        final AtomicLong total = new AtomicLong(0);
        final Map<Integer, MutableLong> testSum = new HashMap<>();
        final Set<Integer> clusteringKeys = new HashSet<>(Arrays.asList(0, 1, 2, 3));
        for (final int clusteringKey : clusteringKeys)
        {
            testSum.put(clusteringKey, new MutableLong(0));
        }

        Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.uuid()).withClusteringKey("b", bridge.aInt()).withColumn("c", bridge.bigint()).withColumn("d", bridge.text()).build())
              .dontWriteRandomData()
              .withSSTableWriter(writer -> {
                  for (int i = 0; i < Tester.DEFAULT_NUM_ROWS; i++)
                  {
                      for (final int clusteringKey : clusteringKeys)
                      {
                          final UUID accountId = java.util.UUID.randomUUID();
                          final long balance = RandomUtils.RANDOM.nextInt(10000000);
                          total.addAndGet(balance);
                          final String name = java.util.UUID.randomUUID().toString().substring(0, 8);
                          testSum.get(clusteringKey).add(balance);
                          writer.write(accountId, clusteringKey, balance, name);
                      }
                  }
              })
              .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS * clusteringKeys.size())
              .withCheck(ds -> {
                  assertEquals(total.get(), testSum.values().stream().mapToLong(MutableLong::getValue).sum());
                  long sum = 0;
                  int count = 0;
                  for (final Row row : ds.collectAsList())
                  {
                      assertNotNull(row.getString(0));
                      final long balance = row.getLong(2);
                      assertNotNull(row.getString(3));
                      sum += balance;
                      count++;
                  }
                  assertEquals(total.get(), sum);
                  assertEquals(Tester.DEFAULT_NUM_ROWS * clusteringKeys.size(), count);
              })
              .withCheck(ds -> {
                  // test basic group by matches expected
                  for (final Row row : ds.groupBy("b").sum("c").collectAsList())
                  {
                      assertEquals(testSum.get(row.getInt(0)).getValue().longValue(), row.getLong(1));
                  }
              })
              .withReset(() -> {
                  total.set(0);
                  for (final int clusteringKey : clusteringKeys)
                  {
                      testSum.put(clusteringKey, new MutableLong(0));
                  }
              })
              .run();
    }

    /* static columns */

    @Test
    public void testOnlyStaticColumn()
    {
        Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.uuid()).withClusteringKey("b", bridge.bigint()).withStaticColumn("c", bridge.aInt()).build())
              .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS)
              .run();
    }

    @SuppressWarnings("UnstableApiUsage") // use of guava Uninterruptibles
    @Test
    public void testStaticColumn()
    {
        final int numRows = 100, numCols = 20;
        Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.aInt())
                                 .withClusteringKey("b", bridge.aInt())
                                 .withStaticColumn("c", bridge.aInt())
                                 .withColumn("d", bridge.text()).build())
              // don't write random data
              .dontWriteRandomData()
              // write some SSTables deterministically
              .withSSTableWriter((writer) -> {
                  for (int i = 0; i < numRows; i++)
                  {
                      for (int j = 0; j < numCols; j++)
                      {
                          // we need to sleep here to prevent timestamp conflicts confusing the static column value
                          if (j == numCols - 1)
                          {
                              Uninterruptibles.sleepUninterruptibly(2, TimeUnit.MILLISECONDS);
                          }
                          writer.write(i, j, i * j, java.util.UUID.randomUUID().toString());
                      }
                  }
              })
              .withSSTableWriter((writer) -> {
                  for (int i = 0; i < numRows; i++)
                  {
                      for (int j = numCols; j < numCols * 2; j++)
                      {
                          // we need to sleep here to prevent timestamp conflicts confusing the static column value
                          if (j == numCols * 2 - 1)
                          {
                              Uninterruptibles.sleepUninterruptibly(2, TimeUnit.MILLISECONDS);
                          }

                          writer.write(i, j, i * j, java.util.UUID.randomUUID().toString());
                      }
                  }
              })
              .withReadListener(row -> {
                  // static column should be the last value written
                  assert (row.getInteger("c") == (row.getInteger("a") * (numCols * 2 - 1)));
              })
              // verify row count is correct
              .withCheck(ds -> assertEquals(numRows * numCols * 2, ds.count()))
              .run();
    }

    @Test
    public void testMultipleSSTableCompacted()
    {
        final TestSchema schema = TestSchema.builder().withPartitionKey("a", bridge.uuid())
                                            .withClusteringKey("b", bridge.aInt()).withClusteringKey("c", bridge.text())
                                            .withColumn("d", bridge.text()).withColumn("e", bridge.bigint()).build();
        final AtomicLong total = new AtomicLong(0);
        final Map<UUID, TestSchema.TestRow> rows = new HashMap<>(Tester.DEFAULT_NUM_ROWS);
        Tester.builder(schema)
              // don't write random data
              .dontWriteRandomData()
              // write some SSTables with random data
              .withSSTableWriter((writer) -> {
                  for (int i = 0; i < Tester.DEFAULT_NUM_ROWS; i++)
                  {
                      final TestSchema.TestRow testRow = schema.randomRow();
                      rows.put(testRow.getUUID("a"), testRow);
                      writer.write(testRow.allValues());
                  }
              })
              // overwrite rows/cells multiple times in different sstables and ensure compaction compacts together correctly
              .withSSTableWriter(writer -> {
                  for (final TestSchema.TestRow testRow : ImmutableSet.copyOf(rows.values()))
                  {
                      // update rows with new values
                      final TestSchema.TestRow newTestRow = testRow.set("e", RandomUtils.RANDOM.nextLong()).set("d", java.util.UUID.randomUUID().toString().substring(0, 10));
                      rows.put(testRow.getUUID("a"), newTestRow);
                      writer.write(newTestRow.allValues());
                  }
              })
              .withSSTableWriter(writer -> {
                  for (final TestSchema.TestRow testRow : ImmutableSet.copyOf(rows.values()))
                  {
                      // update rows with new values - this should be the final values seen by Spark
                      final TestSchema.TestRow newTestRow = testRow.set("e", RandomUtils.RANDOM.nextLong()).set("d", java.util.UUID.randomUUID().toString().substring(0, 10));
                      rows.put(testRow.getUUID("a"), newTestRow);
                      total.addAndGet(newTestRow.getLong("e"));
                      writer.write(newTestRow.allValues());
                  }
              })
              // verify rows returned by Spark match expected
              .withReadListener(actualRow -> assertTrue(rows.containsKey(actualRow.getUUID("a"))))
              .withReadListener(actualRow -> assertEquals(rows.get(actualRow.getUUID("a")), actualRow))
              .withReadListener(actualRow -> assertEquals(rows.get(actualRow.getUUID("a")).getLong("e"), actualRow.getLong("e")))
              // verify Spark aggregations match expected
              .withCheck(ds -> assertEquals(total.get(), ds.groupBy().sum("e").first().getLong(0)))
              .withCheck(ds -> assertEquals(rows.size(), ds.groupBy().count().first().getLong(0)))
              .withReset(() -> {
                  total.set(0);
                  rows.clear();
              })
              .run();
    }

    /* Tombstone tests */

    @Test
    public void testPartitionTombstoneInt()
    {
        final int numRows = 100, numCols = 10;
        qt().withExamples(20).forAll(integers().between(0, numRows - 2))
            .checkAssert(deleteRangeStart -> {
                assert (deleteRangeStart >= 0 && deleteRangeStart < numRows);
                final int deleteRangeEnd = deleteRangeStart + RandomUtils.RANDOM.nextInt(numRows - deleteRangeStart - 1) + 1;
                assert (deleteRangeEnd > deleteRangeStart && deleteRangeEnd < numRows);

                Tester.builder(TestSchema.basicBuilder(bridge).withDeleteFields("a =").build())
                      .withVersions(TestUtils.tombstoneTestableVersions())
                      .dontWriteRandomData()
                      .withSSTableWriter(writer -> {
                          for (int i = 0; i < numRows; i++)
                          {
                              for (int j = 0; j < numCols; j++)
                              {
                                  writer.write(i, j, j);
                              }
                          }
                      })
                      .withTombstoneWriter(writer -> {
                          for (int i = deleteRangeStart; i < deleteRangeEnd; i++)
                          {
                              writer.write(i);
                          }
                      })
                      .dontCheckNumSSTables()
                      .withCheck(ds -> {
                          int count = 0;
                          for (final Row row : ds.collectAsList())
                          {
                              final int value = row.getInt(0);
                              assertTrue(value >= 0 && value < numRows);
                              assertTrue(value < deleteRangeStart || value >= deleteRangeEnd);
                              count++;
                          }
                          assertEquals((numRows - (deleteRangeEnd - deleteRangeStart)) * numCols, count);
                      })
                      .run();
            });
    }

    @Test
    public void testRowTombstoneInt()
    {
        final int numRows = 100, numCols = 10;
        qt().withExamples(20).forAll(integers().between(0, numCols - 1))
            .checkAssert(colNum ->
                         Tester.builder(TestSchema.basicBuilder(bridge).withDeleteFields("a =", "b =").build())
                               .withVersions(TestUtils.tombstoneTestableVersions())
                               .dontWriteRandomData()
                               .withSSTableWriter(writer -> {
                                   for (int i = 0; i < numRows; i++)
                                   {
                                       for (int j = 0; j < numCols; j++)
                                       {
                                           writer.write(i, j, j);
                                       }
                                   }
                               })
                               .withTombstoneWriter(writer -> {
                                   for (int i = 0; i < numRows; i++)
                                   {
                                       writer.write(i, colNum);
                                   }
                               })
                               .dontCheckNumSSTables()
                               .withCheck(ds -> {
                                   int count = 0;
                                   for (final Row row : ds.collectAsList())
                                   {
                                       final int value = row.getInt(0);
                                       assertTrue(row.getInt(0) >= 0 && value < numRows);
                                       assertTrue(row.getInt(1) != colNum);
                                       assertEquals(row.get(1), row.get(2));
                                       count++;
                                   }
                                   assertEquals(numRows * (numCols - 1), count);
                               })
                               .run());
    }

    @Test
    public void testRangeTombstoneInt()
    {
        final int numRows = 10, numCols = 128;
        qt().withExamples(10).forAll(integers().between(0, numCols - 1))
            .checkAssert(startBound -> {
                assertTrue(startBound < numCols);
                final int endBound = startBound + RandomUtils.RANDOM.nextInt(numCols - startBound);
                assertTrue(endBound >= startBound && endBound <= numCols);
                final int numTombstones = endBound - startBound;

                Tester.builder(TestSchema.basicBuilder(bridge).withDeleteFields("a =", "b >=", "b <").build())
                      .withVersions(TestUtils.tombstoneTestableVersions())
                      .dontWriteRandomData()
                      .withSSTableWriter(writer -> {
                          for (int i = 0; i < numRows; i++)
                          {
                              for (int j = 0; j < numCols; j++)
                              {
                                  writer.write(i, j, j);
                              }
                          }
                      })
                      .withTombstoneWriter(writer -> {
                          for (int i = 0; i < numRows; i++)
                          {
                              writer.write(i, startBound, endBound);
                          }
                      })
                      .dontCheckNumSSTables()
                      .withCheck(ds -> {
                          int count = 0;
                          for (final Row row : ds.collectAsList())
                          {
                              // verify row values exist within correct range with range tombstoned values removed
                              final int value = row.getInt(1);
                              assertEquals(value, row.getInt(2));
                              assertTrue(value <= numCols);
                              assertTrue(value < startBound || value >= endBound);
                              count++;
                          }
                          assertEquals(numRows * (numCols - numTombstones), count);
                      })
                      .run();
            });
    }

    @Test
    public void testRangeTombstoneString()
    {
        final int numRows = 10, numCols = 128;
        qt().withExamples(10).forAll(characters().ascii())
            .checkAssert(startBound -> {
                assertTrue(startBound <= numCols);
                final char endBound = (char) (startBound + RandomUtils.RANDOM.nextInt(numCols - startBound));
                assertTrue(endBound >= startBound && endBound <= numCols);
                final int numTombstones = endBound - startBound;

                Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.aInt()).withClusteringKey("b", bridge.text()).withColumn("c", bridge.aInt()).withDeleteFields("a =", "b >=", "b <").build())
                      .withVersions(TestUtils.tombstoneTestableVersions())
                      .dontWriteRandomData()
                      .withSSTableWriter(writer -> {
                          for (int i = 0; i < numRows; i++)
                          {
                              for (int j = 0; j < numCols; j++)
                              {
                                  final String value = String.valueOf((char) j);
                                  writer.write(i, value, j);
                              }
                          }
                      })
                      .withTombstoneWriter(writer -> {
                          for (int i = 0; i < numRows; i++)
                          {
                              writer.write(i, startBound.toString(), Character.toString(endBound));
                          }
                      })
                      .dontCheckNumSSTables()
                      .withCheck(ds -> {
                          int count = 0;
                          for (final Row row : ds.collectAsList())
                          {
                              // verify row values exist within correct range with range tombstoned values removed
                              final char c = row.getString(1).charAt(0);
                              assertTrue(c <= numCols);
                              assertTrue(c < startBound || c >= endBound);
                              count++;
                          }
                          assertEquals(numRows * (numCols - numTombstones), count);
                      })
                      .run();
            });
    }

    /* Partial rows: test reading rows with missing columns */

    @Test
    public void testPartialRow()
    {
        final Map<UUID, UUID> rows = new HashMap<>();
        Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.uuid())
                                 .withColumn("b", bridge.text()).withColumn("c", bridge.uuid()).withColumn("d", bridge.aInt()).withColumn("e", bridge.uuid()).withColumn("f", bridge.aInt())
                                 .withInsertFields("a", "c", "e") // override insert statement to only insert some columns
                                 .build())
              .dontWriteRandomData()
              .withSSTableWriter(writer -> {
                  for (int i = 0; i < Tester.DEFAULT_NUM_ROWS; i++)
                  {
                      final UUID key = java.util.UUID.randomUUID(), value = java.util.UUID.randomUUID();
                      rows.put(key, value);
                      writer.write(key, value, value);
                  }
              })
              .withCheck((ds) -> {
                  for (final Row row : ds.collectAsList())
                  {
                      assertEquals(6, row.size());
                      final UUID key = java.util.UUID.fromString(row.getString(0));
                      final UUID value1 = java.util.UUID.fromString(row.getString(2));
                      final UUID value2 = java.util.UUID.fromString(row.getString(4));
                      assertTrue(rows.containsKey(key));
                      assertEquals(rows.get(key), value1);
                      assertEquals(value2, value1);
                      assertNull(row.get(1));
                      assertNull(row.get(3));
                      assertNull(row.get(5));
                  }
              })
              .withReset(rows::clear)
              .run();
    }

    @Test
    public void testPartialRowClusteringKeys()
    {
        final Map<String, String> rows = new HashMap<>();
        Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.uuid())
                                 .withClusteringKey("b", bridge.uuid()).withClusteringKey("c", bridge.uuid())
                                 .withColumn("d", bridge.text()).withColumn("e", bridge.uuid()).withColumn("f", bridge.aInt()).withColumn("g", bridge.uuid()).withColumn("h", bridge.aInt())
                                 .withInsertFields("a", "b", "c", "e", "g") // override insert statement to only insert some columns
                                 .build())
              .dontWriteRandomData()
              .withSSTableWriter(writer -> {
                  for (int i = 0; i < Tester.DEFAULT_NUM_ROWS; i++)
                  {
                      final UUID a = java.util.UUID.randomUUID(), b = java.util.UUID.randomUUID(), c = java.util.UUID.randomUUID(), e = java.util.UUID.randomUUID(), g = java.util.UUID.randomUUID();
                      final String key = a + ":" + b + ":" + c;
                      final String value = e + ":" + g;
                      rows.put(key, value);
                      writer.write(a, b, c, e, g);
                  }
              })
              .withCheck((ds) -> {
                  for (final Row row : ds.collectAsList())
                  {
                      assertEquals(8, row.size());
                      final String a = row.getString(0), b = row.getString(1), c = row.getString(2), e = row.getString(4), g = row.getString(6);
                      final String key = a + ":" + b + ":" + c;
                      final String value = e + ":" + g;
                      assertTrue(rows.containsKey(key));
                      assertEquals(rows.get(key), value);
                      assertNull(row.get(3));
                      assertNull(row.get(5));
                      assertNull(row.get(7));
                  }
              })
              .withReset(rows::clear)
              .run();
    }

    // collections

    @Test
    public void testSet()
    {
        qt().forAll(TestUtils.cql3Type(bridge))
            .checkAssert((type) ->
                         Tester.builder(TestSchema.builder().withPartitionKey("pk", bridge.uuid()).withColumn("a", bridge.set(type)).build())
                               .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS)
                               .run());
    }

    @Test
    public void testList()
    {
        qt().forAll(TestUtils.cql3Type(bridge))
            .checkAssert((type) ->
                         Tester.builder(TestSchema.builder().withPartitionKey("pk", bridge.uuid()).withColumn("a", bridge.list(type)).build())
                               .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS)
                               .run());
    }

    @Test
    public void testMap()
    {
        qt().withExamples(50) // limit number of tests otherwise n x n tests takes too long
            .forAll(TestUtils.cql3Type(bridge), TestUtils.cql3Type(bridge))
            .checkAssert((keyType, valueType) -> Tester.builder(TestSchema.builder().withPartitionKey("pk", bridge.uuid()).withColumn("a", bridge.map(keyType, valueType)).build())
                                                       .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS)
                                                       .run());
    }

    @Test
    public void testClusteringKeySet()
    {
        Tester.builder(TestSchema.builder().withPartitionKey("pk", bridge.uuid()).withClusteringKey("id", bridge.aInt()).withColumn("a", bridge.set(bridge.text())).build())
              .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS)
              .run();
    }

    // frozen collections

    @Test
    public void testFrozenSet()
    {
        // pk -> a frozen<set<?>>
        qt().forAll(TestUtils.cql3Type(bridge))
            .checkAssert((type) ->
                         Tester.builder(TestSchema.builder().withPartitionKey("pk", bridge.uuid()).withColumn("a", bridge.set(type).frozen()).build())
                               .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS)
                               .run()
            );
    }

    @Test
    public void testFrozenList()
    {
        // pk -> a frozen<list<?>>
        qt().forAll(TestUtils.cql3Type(bridge))
            .checkAssert((type) ->
                         Tester.builder(TestSchema.builder().withPartitionKey("pk", bridge.uuid()).withColumn("a", bridge.list(type).frozen()).build())
                               .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS)
                               .run()
            );
    }

    @Test
    public void testFrozenMap()
    {
        // pk -> a frozen<map<?, ?>>
        qt().withExamples(50) // limit number of tests otherwise n x n tests takes too long
            .forAll(TestUtils.cql3Type(bridge), TestUtils.cql3Type(bridge))
            .checkAssert((keyType, valueType) ->
                         Tester.builder(TestSchema.builder().withPartitionKey("pk", bridge.uuid()).withColumn("a", bridge.map(keyType, valueType).frozen()).build())
                               .withExpectedRowCountPerSSTable(Tester.DEFAULT_NUM_ROWS)
                               .run());
    }

    @Test
    public void testNestedMapSet()
    {
        // pk -> a map<text, frozen<set<text>>>
        Tester.builder(TestSchema.builder().withPartitionKey("pk", bridge.uuid()).withColumn("a", bridge.map(bridge.text(), bridge.set(bridge.text()).frozen())).build())
              .withNumRandomRows(32)
              .withExpectedRowCountPerSSTable(32)
              .run();
    }

    @Test
    public void testNestedMapList()
    {
        // pk -> a map<text, frozen<list<text>>>
        Tester.builder(TestSchema.builder().withPartitionKey("pk", bridge.uuid()).withColumn("a", bridge.map(bridge.text(), bridge.list(bridge.text()).frozen())).build())
              .withNumRandomRows(32)
              .withExpectedRowCountPerSSTable(32)
              .run();
    }

    @Test
    public void testNestedMapMap()
    {
        // pk -> a map<text, frozen<map<bigint, varchar>>>
        Tester.builder(TestSchema.builder().withPartitionKey("pk", bridge.uuid()).withColumn("a", bridge.map(bridge.text(), bridge.map(bridge.bigint(), bridge.varchar()).frozen())).build())
              .withNumRandomRows(32)
              .withExpectedRowCountPerSSTable(32)
              .dontCheckNumSSTables()
              .run();
    }

    @Test
    public void testFrozenNestedMapMap()
    {
        // pk -> a frozen<map<text, <map<int, timestamp>>>
        Tester.builder(TestSchema.builder().withPartitionKey("pk", bridge.uuid()).withColumn("a", bridge.map(bridge.text(), bridge.map(bridge.aInt(), bridge.timestamp())).frozen()).build())
              .withNumRandomRows(32)
              .withExpectedRowCountPerSSTable(32)
              .dontCheckNumSSTables()
              .run();
    }

    // filters

    @Test
    public void testSinglePartitionKeyFilter()
    {
        final int numRows = 10;
        Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.aInt()).withColumn("b", bridge.aInt()).build())
              .dontWriteRandomData()
              .withSSTableWriter(writer -> {
                  for (int i = 0; i < numRows; i++)
                  {
                      writer.write(i, i + 1);
                  }
              })
              .withFilter("a=1")
              .withCheck((ds) -> {
                  for (final Row row : ds.collectAsList())
                  {
                      final int a = row.getInt(0);
                      assertEquals(1, a);
                  }
              })
              .run();
    }

    @Test
    public void testMultiplePartitionKeyFilter()
    {
        final int numRows = 10, numCols = 5;
        final Set<String> keys = TestUtils.getKeys(Arrays.asList(Arrays.asList("2", "3"), Arrays.asList("2", "3", "4")));
        Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.aInt()).withPartitionKey("b", bridge.aInt()).withColumn("c", bridge.aInt()).build())
              .dontWriteRandomData()
              .withSSTableWriter(writer -> {
                  for (int i = 0; i < numRows; i++)
                  {
                      for (int j = 0; j < numCols; j++)
                      {
                          writer.write(i, i + 1, j);
                      }
                  }
              })
              .withFilter("a in (2, 3) and b in (2, 3, 4)")
              .withCheck((ds) -> {
                  for (final Row row : ds.collectAsList())
                  {
                      final int a = row.getInt(0), b = row.getInt(1);
                      final String key = a + ":" + b;
                      assertTrue(keys.contains(key));
                  }
              })
              .run();
    }

    @Test
    public void testFiltersDoNotMatch()
    {
        final int numRows = 10;
        Tester.builder(TestSchema.builder().withPartitionKey("a", bridge.aInt()).withColumn("b", bridge.aInt()).build())
              .dontWriteRandomData()
              .withSSTableWriter(writer -> {
                  for (int i = 0; i < numRows; i++)
                  {
                      writer.write(i, i + 1);
                  }
              })
              .withFilter("a=11")
              .withCheck((ds) -> assertTrue(ds.collectAsList().isEmpty()))
              .run();
    }

    @Test
    public void testUdtNativeTypes()
    {
        // pk -> a testudt<b text, c type, d int>
        qt().forAll(TestUtils.cql3Type(bridge)).checkAssert((type) -> Tester.builder(
        TestSchema.builder()
                  .withPartitionKey("pk", bridge.uuid())
                  .withColumn("a", bridge.udt("keyspace", "testudt")
                                         .withField("b", bridge.text())
                                         .withField("c", type)
                                         .withField("d", bridge.aInt()).build())
                  .build()).run()
        );
    }

    @Test
    public void testUdtInnerSet()
    {
        // pk -> a testudt<b text, c frozen<type>, d int>
        qt().forAll(TestUtils.cql3Type(bridge))
            .checkAssert((type) ->
                         Tester.builder(
                         TestSchema.builder()
                                   .withPartitionKey("pk", bridge.uuid())
                                   .withColumn("a", bridge.udt("keyspace", "testudt")
                                                          .withField("b", bridge.text())
                                                          .withField("c", bridge.set(type).frozen())
                                                          .withField("d", bridge.aInt()).build())
                                   .build()).run()
            );
    }

    @Test
    public void testUdtInnerList()
    {
        // pk -> a testudt<b bigint, c frozen<list<type>>, d boolean>
        qt().forAll(TestUtils.cql3Type(bridge))
            .checkAssert((type) ->
                         Tester.builder(
                         TestSchema.builder()
                                   .withPartitionKey("pk", bridge.uuid())
                                   .withColumn("a", bridge.udt("keyspace", "testudt")
                                                          .withField("b", bridge.bigint())
                                                          .withField("c", bridge.list(type).frozen())
                                                          .withField("d", bridge.bool()).build())
                                   .build()).run()
            );
    }

    @Test
    public void testUdtInnerMap()
    {
        // pk -> a testudt<b float, c frozen<set<uuid>>, d frozen<map<type1, type2>>, e boolean>
        qt().withExamples(50)
            .forAll(TestUtils.cql3Type(bridge), TestUtils.cql3Type(bridge))
            .checkAssert((type1, type2) ->
                         Tester.builder(
                         TestSchema.builder()
                                   .withPartitionKey("pk", bridge.uuid())
                                   .withColumn("a", bridge.udt("keyspace", "testudt")
                                                          .withField("b", bridge.aFloat())
                                                          .withField("c", bridge.set(bridge.uuid()).frozen())
                                                          .withField("d", bridge.map(type1, type2).frozen())
                                                          .withField("e", bridge.bool())
                                                          .build())
                                   .build()).run()
            );
    }

    @Test
    public void testMultipleUdts()
    {
        // pk -> col1 udt1<a float, b frozen<set<uuid>>, c frozen<set<type>>, d boolean>, col2 udt2<a text, b bigint, g varchar>, col3 udt3<int, type, ascii>
        qt()
        .forAll(TestUtils.cql3Type(bridge))
        .checkAssert((type) ->
                     Tester.builder(
                     TestSchema.builder()
                               .withPartitionKey("pk", bridge.uuid())
                               .withColumn("col1", bridge.udt("keyspace", "udt1")
                                                         .withField("a", bridge.aFloat())
                                                         .withField("b", bridge.set(bridge.uuid()).frozen())
                                                         .withField("c", bridge.set(type).frozen())
                                                         .withField("d", bridge.bool())
                                                         .build())
                               .withColumn("col2", bridge.udt("keyspace", "udt2")
                                                         .withField("a", bridge.text())
                                                         .withField("b", bridge.bigint())
                                                         .withField("g", bridge.varchar())
                                                         .build())
                               .withColumn("col3", bridge.udt("keyspace", "udt3")
                                                         .withField("a", bridge.aInt())
                                                         .withField("b", bridge.list(type).frozen())
                                                         .withField("c", bridge.ascii())
                                                         .build())
                               .build()).run()
        );
    }

    @Test
    public void testNestedUdt()
    {
        // pk -> a test_udt<b float, c frozen<set<uuid>>, d frozen<nested_udt<x int, y type, z int>>, e boolean>
        qt()
        .forAll(TestUtils.cql3Type(bridge))
        .checkAssert((type) ->
                     Tester.builder(
                     TestSchema.builder()
                               .withPartitionKey("pk", bridge.uuid())
                               .withColumn("a", bridge.udt("keyspace", "test_udt")
                                                      .withField("b", bridge.aFloat())
                                                      .withField("c", bridge.set(bridge.uuid()).frozen())
                                                      .withField("d", bridge.udt("keyspace", "nested_udt")
                                                                            .withField("x", bridge.aInt())
                                                                            .withField("y", type)
                                                                            .withField("z", bridge.aInt())
                                                                            .build().frozen())
                                                      .withField("e", bridge.bool())
                                                      .build())
                               .build())
                           .run()
        );
    }

    // tuples

    @Test
    public void testBasicTuple()
    {
        // pk -> a tuple<int, type1, bigint, type2>
        qt().withExamples(10)
            .forAll(TestUtils.cql3Type(bridge), TestUtils.cql3Type(bridge))
            .checkAssert((type1, type2) ->
                         Tester.builder(
                         TestSchema.builder()
                                   .withPartitionKey("pk", bridge.uuid())
                                   .withColumn("a", bridge.tuple(bridge.aInt(), type1, bridge.bigint(), type2))
                                   .build())
                               .run()
            );
    }

    @Test
    public void testTupleWithClusteringKey()
    {
        // pk -> col1 type1 -> a tuple<int, type2, bigint>
        qt().withExamples(10)
            .forAll(TestUtils.cql3Type(bridge), TestUtils.cql3Type(bridge))
            .checkAssert((type1, type2) ->
                         Tester.builder(
                         TestSchema.builder()
                                   .withPartitionKey("pk", bridge.uuid())
                                   .withClusteringKey("col1", type1)
                                   .withColumn("a", bridge.tuple(bridge.aInt(), type2, bridge.bigint()))
                                   .build())
                               .run()
            );
    }

    @Test
    public void testNestedTuples()
    {
        // pk -> a tuple<varchar, tuple<int, type1, float, varchar, tuple<bigint, boolean, type2>>, timeuuid>
        // test tuples nested within tuple
        qt().withExamples(10)
            .forAll(TestUtils.cql3Type(bridge), TestUtils.cql3Type(bridge))
            .checkAssert((type1, type2) ->
                         Tester.builder(
                         TestSchema.builder()
                                   .withPartitionKey("pk", bridge.uuid())
                                   .withColumn("a", bridge.tuple(bridge.varchar(), bridge.tuple(bridge.aInt(), type1, bridge.aFloat(), bridge.varchar(), bridge.tuple(bridge.bigint(), bridge.bool(), type2)), bridge.timeuuid()))
                                   .build())
                               .run()
            );
    }

    @Test
    public void testTupleSet()
    {
        // pk -> a tuple<varchar, tuple<int, varchar, float, varchar, set<type>>, timeuuid>
        // test set nested within tuple
        qt().withExamples(10)
            .forAll(TestUtils.cql3Type(bridge))
            .checkAssert((type) ->
                         Tester.builder(
                         TestSchema.builder()
                                   .withPartitionKey("pk", bridge.uuid())
                                   .withColumn("a", bridge.tuple(bridge.varchar(), bridge.tuple(bridge.aInt(), bridge.varchar(), bridge.aFloat(), bridge.varchar(), bridge.set(type)), bridge.timeuuid()))
                                   .build())
                               .run()
            );
    }

    @Test
    public void testTupleList()
    {
        // pk -> a tuple<varchar, tuple<int, varchar, float, varchar, list<type>>, timeuuid>
        // test list nested within tuple
        qt().withExamples(10)
            .forAll(TestUtils.cql3Type(bridge))
            .checkAssert((type) ->
                         Tester.builder(
                         TestSchema.builder()
                                   .withPartitionKey("pk", bridge.uuid())
                                   .withColumn("a", bridge.tuple(bridge.varchar(), bridge.tuple(bridge.aInt(), bridge.varchar(), bridge.aFloat(), bridge.varchar(), bridge.list(type)), bridge.timeuuid()))
                                   .build())
                               .run()
            );
    }

    @Test
    public void testTupleMap()
    {
        // pk -> a tuple<varchar, tuple<int, varchar, float, varchar, map<type1, type2>>, timeuuid>
        // test map nested within tuple
        qt().withExamples(10)
            .forAll(TestUtils.cql3Type(bridge), TestUtils.cql3Type(bridge))
            .checkAssert((type1, type2) ->
                         Tester.builder(
                         TestSchema.builder()
                                   .withPartitionKey("pk", bridge.uuid())
                                   .withColumn("a", bridge.tuple(bridge.varchar(), bridge.tuple(bridge.aInt(), bridge.varchar(), bridge.aFloat(), bridge.varchar(), bridge.map(type1, type2)), bridge.timeuuid()))
                                   .build())
                               .run()
            );
    }

    @Test
    public void testMapTuple()
    {
        // pk -> a map<timeuuid, frozen<tuple<boolean, type, timestamp>>>
        // test tuple nested within map
        qt().withExamples(10)
            .forAll(TestUtils.cql3Type(bridge))
            .checkAssert((type) ->
                         Tester.builder(
                         TestSchema.builder()
                                   .withPartitionKey("pk", bridge.uuid())
                                   .withColumn("a", bridge.map(bridge.timeuuid(), bridge.tuple(bridge.bool(), type, bridge.timestamp()).frozen()))
                                   .build())
                               .run()
            );
    }

    @Test
    public void testSetTuple()
    {
//         pk -> a set<frozen<tuple<type, float, text>>>
//         test tuple nested within set
        qt().withExamples(10)
            .forAll(TestUtils.cql3Type(bridge))
            .checkAssert((type) ->
                         Tester.builder(
                         TestSchema.builder()
                                   .withPartitionKey("pk", bridge.uuid())
                                   .withColumn("a", bridge.set(bridge.tuple(type, bridge.aFloat(), bridge.text()).frozen()))
                                   .build())
                               .run()
            );
    }

    @Test
    public void testListTuple()
    {
        // pk -> a list<frozen<tuple<int, inet, decimal, type>>>
        // test tuple nested within map
        qt().withExamples(10)
            .forAll(TestUtils.cql3Type(bridge))
            .checkAssert((type) ->
                         Tester.builder(
                         TestSchema.builder()
                                   .withPartitionKey("pk", bridge.uuid())
                                   .withColumn("a", bridge.list(bridge.tuple(bridge.aInt(), bridge.inet(), bridge.decimal(), type).frozen()))
                                   .build())
                               .run()
            );
    }


    @Test
    public void testTupleUDT()
    {
        // pk -> a tuple<varchar, frozen<nested_udt<x int, y type, z int>>, timeuuid>
        // test tuple with inner UDT
        qt().withExamples(10)
            .forAll(TestUtils.cql3Type(bridge))
            .checkAssert((type) ->
                         Tester.builder(
                         TestSchema.builder()
                                   .withPartitionKey("pk", bridge.uuid())
                                   .withColumn("a", bridge.tuple(bridge.varchar(), bridge.udt("keyspace", "nested_udt")
                                                                                                .withField("x", bridge.aInt())
                                                                                                .withField("y", type)
                                                                                                .withField("z", bridge.aInt())
                                                                                                .build().frozen(), bridge.timeuuid()))
                                   .build())
                               .run()
            );
    }

    @Test
    public void testUDTTuple()
    {
        // pk -> a nested_udt<x text, y tuple<int, float, type, timestamp>, z ascii>
        // test UDT with inner tuple
        qt().withExamples(10)
            .forAll(TestUtils.cql3Type(bridge))
            .checkAssert((type) ->
                         Tester.builder(
                         TestSchema.builder()
                                   .withPartitionKey("pk", bridge.uuid())
                                   .withColumn("a", bridge.udt("keyspace", "nested_udt")
                                                          .withField("x", bridge.text())
                                                          .withField("y", bridge.tuple(bridge.aInt(), bridge.aFloat(), type, bridge.timestamp()))
                                                          .withField("z", bridge.ascii())
                                                          .build())
                                   .build())
                               .run()
            );
    }

    @Test
    public void testTupleClusteringKey()
    {
        qt()
        .forAll(TestUtils.cql3Type(bridge))
        .checkAssert((type) ->
                     Tester.builder(
                     TestSchema.builder()
                               .withPartitionKey("pk", bridge.uuid())
                               .withClusteringKey("ck", bridge.tuple(bridge.aInt(), bridge.text(), type, bridge.aFloat()))
                               .withColumn("a", bridge.text())
                               .withColumn("b", bridge.aInt())
                               .withColumn("c", bridge.ascii())
                               .build())
                           .run()
        );
    }

    @Test
    public void testUdtClusteringKey()
    {
        qt()
        .forAll(TestUtils.cql3Type(bridge))
        .checkAssert((type) ->
                     Tester.builder(
                     TestSchema.builder()
                               .withPartitionKey("pk", bridge.uuid())
                               .withClusteringKey("ck", bridge.udt("keyspace", "udt1")
                                                              .withField("a", bridge.text())
                                                              .withField("b", type)
                                                              .withField("c", bridge.aInt())
                                                              .build().frozen())
                               .withColumn("a", bridge.text())
                               .withColumn("b", bridge.aInt())
                               .withColumn("c", bridge.ascii())
                               .build())
                           .run()
        );
    }

    @Test
    public void testComplexSchema()
    {
        final String keyspace = "complex_schema2";
        final CqlField.CqlUdt udt1 = bridge.udt(keyspace, "udt1")
                                  .withField("time", bridge.bigint())
                                  .withField("\"time_offset_minutes\"", bridge.aInt())
                                  .build();
        final CqlField.CqlUdt udt2 = bridge.udt(keyspace, "udt2")
                                  .withField("\"version\"", bridge.text())
                                  .withField("\"id\"", bridge.text())
                                  .withField("platform", bridge.text())
                                  .withField("time_range", bridge.text())
                                  .build();
        final CqlField.CqlUdt udt3 = bridge.udt(keyspace, "udt3")
                                  .withField("field", bridge.text())
                                  .withField("\"time_with_zone\"", udt1)
                                  .build();
        final CqlField.CqlUdt udt4 = bridge.udt(keyspace, "udt4")
                                  .withField("\"first_seen\"", udt3.frozen())
                                  .withField("\"last_seen\"", udt3.frozen())
                                  .withField("\"first_transaction\"", udt3.frozen())
                                  .withField("\"last_transaction\"", udt3.frozen())
                                  .withField("\"first_listening\"", udt3.frozen())
                                  .withField("\"last_listening\"", udt3.frozen())
                                  .withField("\"first_reading\"", udt3.frozen())
                                  .withField("\"last_reading\"", udt3.frozen())
                                  .withField("\"output_event\"", bridge.text())
                                  .withField("\"event_history\"", bridge.map(bridge.bigint(), bridge.map(bridge.text(), bridge.bool()).frozen()).frozen())
                                  .build();

        Tester.builder(TestSchema.builder()
                                 .withKeyspace(keyspace)
                                 .withTable("complex_table")
                                 .withPartitionKey("\"consumerId\"", bridge.text())
                                 .withClusteringKey("dimensions", udt2.frozen())
                                 .withColumn("fields", udt4.frozen())
                                 .withColumn("first_transition_time", udt1.frozen())
                                 .withColumn("last_transition_time", udt1.frozen())
                                 .withColumn("prev_state_id", bridge.text())
                                 .withColumn("state_id", bridge.text())
                                 .build())
              .run();
    }

    @Test
    public void testNestedFrozenUDT()
    {
        // "(a bigint PRIMARY KEY, b <map<int, frozen<testudt>>>)"
        // testudt(a text, b bigint, c int)
        final String keyspace = "nested_frozen_udt";
        final CqlField.CqlUdt testudt = bridge.udt(keyspace, "testudt")
                                     .withField("a", bridge.text())
                                     .withField("b", bridge.bigint())
                                     .withField("c", bridge.aInt())
                                     .build();
        Tester.builder(TestSchema.builder()
                                 .withKeyspace(keyspace)
                                 .withPartitionKey("a", bridge.bigint())
                                 .withColumn("b", bridge.map(bridge.aInt(), testudt.frozen()))
                                 .build())
              .run();
    }

    @Test
    public void testDeepNestedUDT()
    {
        final String keyspace = "deep_nested_frozen_udt";
        final CqlField.CqlUdt udt1 = bridge.udt(keyspace, "udt1")
                                  .withField("a", bridge.text())
                                  .withField("b", bridge.aInt())
                                  .withField("c", bridge.bigint()).build();
        final CqlField.CqlUdt udt2 = bridge.udt(keyspace, "udt2")
                                  .withField("a", bridge.aInt())
                                  .withField("b", bridge.set(bridge.uuid()))
                                  .build();
        final CqlField.CqlUdt udt3 = bridge.udt(keyspace, "udt3")
                                  .withField("a", bridge.aInt())
                                  .withField("b", bridge.set(bridge.uuid()))
                                  .build();
        final CqlField.CqlUdt udt4 = bridge.udt(keyspace, "udt4")
                                  .withField("a", bridge.text())
                                  .withField("b", bridge.text())
                                  .withField("c", bridge.uuid())
                                  .withField("d", bridge.list(bridge.tuple(bridge.text(), bridge.bigint()).frozen()).frozen())
                                  .build();
        final CqlField.CqlUdt udt5 = bridge.udt(keyspace, "udt5")
                                  .withField("a", bridge.text())
                                  .withField("b", bridge.text())
                                  .withField("c", bridge.bigint())
                                  .withField("d", bridge.set(udt4.frozen()))
                                  .build();
        final CqlField.CqlUdt udt6 = bridge.udt(keyspace, "udt6")
                                  .withField("a", bridge.text())
                                  .withField("b", bridge.text())
                                  .withField("c", bridge.aInt())
                                  .withField("d", bridge.aInt())
                                  .build();
        final CqlField.CqlUdt udt7 = bridge.udt(keyspace, "udt7")
                                  .withField("a", bridge.text())
                                  .withField("b", bridge.uuid())
                                  .withField("c", bridge.bool())
                                  .withField("d", bridge.bool())
                                  .withField("e", bridge.bool())
                                  .withField("f", bridge.bigint())
                                  .withField("g", bridge.bigint())
                                  .build();

        final CqlField.CqlUdt udt8 = bridge.udt(keyspace, "udt8")
                                  .withField("a", bridge.text())
                                  .withField("b", bridge.bool())
                                  .withField("c", bridge.bool())
                                  .withField("d", bridge.bool())
                                  .withField("e", bridge.bigint())
                                  .withField("f", bridge.bigint())
                                  .withField("g", bridge.uuid())
                                  .withField("h", bridge.bigint())
                                  .withField("i", bridge.uuid())
                                  .withField("j", bridge.uuid())
                                  .withField("k", bridge.uuid())
                                  .withField("l", bridge.uuid())
                                  .withField("m", bridge.aInt())
                                  .withField("n", bridge.timestamp())
                                  .withField("o", bridge.text())
                                  .build();

        Tester.builder(
        TestSchema.builder()
                  .withKeyspace(keyspace)
                  .withTable("info")
                  .withPartitionKey("pk", bridge.uuid())
                  .withClusteringKey("ck", bridge.uuid())
                  .withColumn("a", udt3.frozen())
                  .withColumn("b", udt2.frozen())
                  .withColumn("c", bridge.set(bridge.tuple(udt1, bridge.text()).frozen()))
                  .withColumn("d", bridge.set(bridge.tuple(bridge.bigint(), bridge.text()).frozen()))
                  .withColumn("e", bridge.set(bridge.tuple(udt2, bridge.text()).frozen()))
                  .withColumn("f", bridge.set(udt7.frozen()))
                  .withColumn("g", bridge.map(bridge.aInt(), bridge.set(bridge.text()).frozen()))
                  .withColumn("h", bridge.set(bridge.tinyint()))
                  .withColumn("i", bridge.map(bridge.text(), udt6.frozen()))
                  .withColumn("j", bridge.map(bridge.text(), bridge.map(bridge.text(), bridge.text()).frozen()))
                  .withColumn("k", bridge.list(bridge.tuple(bridge.text(), bridge.text(), bridge.text()).frozen()))
                  .withColumn("l", bridge.list(udt5.frozen()))
                  .withColumn("m", udt8.frozen())
                  .withMinCollectionSize(4)
                  .build())
              .withNumRandomRows(50)
              .withNumRandomSSTables(2)
              .run();
    }


    // big decimal/integer tests

    @Test
    public void testBigDecimal()
    {
        Tester.builder(TestSchema.builder().withPartitionKey("pk", bridge.uuid()).withColumn("c1", bridge.decimal()).withColumn("c2", bridge.text()).build())
              .run();
    }

    @Test
    public void testBigInteger()
    {
        Tester.builder(TestSchema.builder().withPartitionKey("pk", bridge.uuid()).withColumn("c1", bridge.varint()).withColumn("c2", bridge.text()).build())
              .run();
    }

    @Test
    public void testUdtFieldOrdering()
    {
        final String keyspace = "udt_field_ordering";
        final CqlField.CqlUdt udt1 = bridge.udt(keyspace, "udt1")
                                  .withField("c", bridge.text())
                                  .withField("b", bridge.uuid())
                                  .withField("a", bridge.bool())
                                  .build();
        Tester.builder(TestSchema.builder()
                                 .withKeyspace(keyspace)
                                 .withPartitionKey("pk", bridge.uuid())
                                 .withColumn("a", bridge.set(udt1.frozen()))
                                 .build()
        ).run();
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testUdtTupleInnerNulls()
    {
        final String keyspace = "udt_inner_nulls";
        final CqlField.CqlUdt udt1 = bridge.udt(keyspace, "udt1")
                                  .withField("a", bridge.uuid())
                                  .withField("b", bridge.text())
                                  .build();
        final CqlField.CqlTuple tuple = bridge.tuple(bridge.bigint(), bridge.text(), bridge.aInt());

        final int numRows = 50;
        final Map<UUID, Set<UDTValue>> aValues = new LinkedHashMap<>(numRows);
        final Map<UUID, TupleValue> bValues = new LinkedHashMap<>(numRows);
        for (int i = 0; i < numRows; i++)
        {
            final java.util.UUID pk = java.util.UUID.randomUUID();
            final Set<UDTValue> value = new HashSet<>(numRows);
            for (int j = 0; j < numRows; j++)
            {
                final Map<String, Object> udtValue = new HashMap<>(2);
                udtValue.put("a", java.util.UUID.randomUUID());
                udtValue.put("b", j >= 25 ? java.util.UUID.randomUUID().toString() : null);
                value.add(CqlUdt.toUserTypeValue(CassandraBridge.CassandraVersion.THREEZERO, (CqlUdt) udt1, udtValue));
            }
            aValues.put(pk, value);
            bValues.put(pk, CqlTuple.toTupleValue(CassandraBridge.CassandraVersion.THREEZERO, (CqlTuple) tuple, new Object[]{ RandomUtils.RANDOM.nextLong(), i > 25 ? null : java.util.UUID.randomUUID().toString(), RandomUtils.RANDOM.nextInt() }));
        }

        Tester.builder(TestSchema.builder()
                                 .withKeyspace(keyspace)
                                 .withPartitionKey("pk", bridge.uuid())
                                 .withColumn("a", bridge.set(udt1.frozen()))
                                 .withColumn("b", tuple)
                                 .build()
        ).withSSTableWriter(writer -> {
            for (final UUID pk : aValues.keySet())
            {
                writer.write(pk, aValues.get(pk), bValues.get(pk));
            }
        }).withCheck(ds -> {
            for (final Row row : ds.collectAsList())
            {
                final java.util.UUID pk = java.util.UUID.fromString(row.getString(0));
                final WrappedArray<Object> ar1 = (WrappedArray<Object>) row.get(1);
                final Set<UDTValue> expectedA = aValues.get(pk);
                assertNotNull(expectedA);
                assertEquals(ar1.length(), expectedA.size());
                for (final Row innerRow : (Row[]) ar1.array())
                {
                    final java.util.UUID a = java.util.UUID.fromString(innerRow.getString(0));
                    final String b = innerRow.getString(1);
                    final Map<String, Object> udtValue = new HashMap<>(2);
                    udtValue.put("a", a);
                    udtValue.put("b", b);
                    assertTrue(expectedA.contains(CqlUdt.toUserTypeValue(CassandraBridge.CassandraVersion.THREEZERO, (CqlUdt) udt1, udtValue)));
                }

                final Row innerTuple = (Row) row.get(2);
                final TupleValue expectedB = bValues.get(pk);
                assertEquals(expectedB.getLong(0), innerTuple.getLong(0));
                assertEquals(expectedB.getString(1), innerTuple.getString(1));
                assertEquals(expectedB.getInt(2), innerTuple.getInt(2));
            }
        })
              .dontWriteRandomData().run();
    }

    // complex clustering keys

    @Test
    public void testUdtsWithNulls()
    {
        final String keyspace = "udt_with_nulls";
        final Map<Long, Map<String, String>> udts = new HashMap<>(Tester.DEFAULT_NUM_ROWS);
        final CqlField.CqlUdt udtType = bridge.udt(keyspace, "udt1").withField("a", bridge.text()).withField("b", bridge.text()).withField("c", bridge.text()).build();
        Tester.builder(TestSchema.builder().withKeyspace(keyspace).withPartitionKey("pk", bridge.bigint())
                                 .withClusteringKey("ck", udtType.frozen())
                                 .withColumn("col1", bridge.text()).withColumn("col2", bridge.timestamp()).withColumn("col3", bridge.aInt())
                                 .build())
              .dontWriteRandomData()
              .withSSTableWriter(writer -> {
                  final int midPoint = Tester.DEFAULT_NUM_ROWS / 2;
                  for (long pk = 0; pk < Tester.DEFAULT_NUM_ROWS; pk++)
                  {
                      final Map<String, String> udt = ImmutableMap.of(pk < midPoint ? "a" : "b", TestUtils.randomValue(bridge.text()).toString(), "c", TestUtils.randomValue(bridge.text()).toString());
                      udts.put(pk, udt);
                      writer.write(pk, CqlUdt.toUserTypeValue(CassandraBridge.CassandraVersion.THREEZERO, (CqlUdt) udtType, udt), TestUtils.randomValue(bridge.text()), TestUtils.randomValue(bridge.timestamp()), TestUtils.randomValue(bridge.aInt()));
                  }
              })
              .withCheck(ds -> {
                  final Map<Long, Row> rows = ds.collectAsList().stream().collect(Collectors.toMap(r -> r.getLong(0), r -> r.getStruct(1)));
                  assertEquals(rows.size(), udts.size());
                  for (final Map.Entry<Long, Row> pk : rows.entrySet())
                  {
                      assertEquals(udts.get(pk.getKey()).get("a"), pk.getValue().getString(0));
                      assertEquals(udts.get(pk.getKey()).get("b"), pk.getValue().getString(1));
                      assertEquals(udts.get(pk.getKey()).get("c"), pk.getValue().getString(2));
                  }
              })
              .run();
    }

    @Test
    public void testMapClusteringKey()
    {
        Tester.builder(TestSchema.builder().withPartitionKey("pk", bridge.uuid()).withClusteringKey("ck", bridge.map(bridge.bigint(), bridge.text()).frozen())
                                 .withColumn("c1", bridge.text()).withColumn("c2", bridge.text()).withColumn("c3", bridge.text()).build())
              .withNumRandomRows(5)
              .run();
    }

    @Test
    public void testListClusteringKey()
    {
        Tester.builder(TestSchema.builder().withPartitionKey("pk", bridge.uuid())
                                 .withClusteringKey("ck", bridge.list(bridge.bigint()).frozen())
                                 .withColumn("c1", bridge.text()).withColumn("c2", bridge.text()).withColumn("c3", bridge.text())
                                 .build()).run();
    }

    @Test
    public void testSetClusteringKey()
    {
        Tester.builder(TestSchema.builder().withPartitionKey("pk", bridge.uuid())
                                 .withClusteringKey("ck", bridge.set(bridge.aFloat()).frozen())
                                 .withColumn("c1", bridge.text()).withColumn("c2", bridge.text()).withColumn("c3", bridge.text())
                                 .build()).run();
    }

    @Test
    public void testUdTClusteringKey()
    {
        final String keyspace = "udt_clustering_key";
        Tester.builder(TestSchema.builder()
                                 .withKeyspace(keyspace).withPartitionKey("pk", bridge.uuid())
                                 .withClusteringKey("ck", bridge.udt(keyspace, "udt1").withField("a", bridge.text()).withField("b", bridge.aFloat()).withField("c", bridge.bigint()).build().frozen())
                                 .withColumn("c1", bridge.text()).withColumn("c2", bridge.text()).withColumn("c3", bridge.text()).build())
              .run();
    }
}
