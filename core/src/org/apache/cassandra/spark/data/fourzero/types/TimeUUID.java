package org.apache.cassandra.spark.data.fourzero.types;

import org.apache.cassandra.spark.shaded.fourzero.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.db.marshal.TimeUUIDType;
import org.apache.cassandra.spark.shaded.fourzero.datastax.driver.core.utils.UUIDs;

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

public class TimeUUID extends UUID
{
    public static final TimeUUID INSTANCE = new TimeUUID();

    @Override
    public String name()
    {
        return "timeuuid";
    }

    @Override
    public AbstractType<?> dataType()
    {
        return TimeUUIDType.instance;
    }

    @Override
    public Object randomValue(int minCollectionSize)
    {
        return UUIDs.timeBased();
    }

    @Override
    public org.apache.cassandra.spark.shaded.fourzero.datastax.driver.core.DataType driverDataType(boolean isFrozen)
    {
        return org.apache.cassandra.spark.shaded.fourzero.datastax.driver.core.DataType.timeuuid();
    }
}
