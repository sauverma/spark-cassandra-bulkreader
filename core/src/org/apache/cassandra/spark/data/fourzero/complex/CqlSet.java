package org.apache.cassandra.spark.data.fourzero.complex;

import org.apache.cassandra.spark.data.CqlField;
import org.apache.cassandra.spark.data.fourzero.FourZeroCqlType;
import org.apache.cassandra.spark.reader.CassandraBridge;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.db.marshal.SetType;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.serializers.SetSerializer;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.serializers.TypeSerializer;
import org.apache.cassandra.spark.shaded.fourzero.datastax.driver.core.SettableByIndexData;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

@SuppressWarnings("unchecked")
public class CqlSet extends CqlList implements CqlField.CqlSet
{
    public CqlSet(CqlField.CqlType type)
    {
        super(type);
    }

    @Override
    public AbstractType<?> dataType(boolean isMultiCell)
    {
        return SetType.getInstance(
        ((FourZeroCqlType) type()).dataType(), isMultiCell
        );
    }

    @Override
    public InternalType internalType()
    {
        return InternalType.Set;
    }

    @Override
    public <T> TypeSerializer<T> serializer()
    {
        return (TypeSerializer<T>) SetSerializer.getInstance(
        ((FourZeroCqlType) type()).serializer(),
        ((FourZeroCqlType) type()).dataType()
        );
    }

    @Override
    public String name()
    {
        return "set";
    }

    @Override
    public Object randomValue(int minCollectionSize)
    {
        return new HashSet<>(((List<Object>) super.randomValue(minCollectionSize)));
    }

    @Override
    public Object toTestRowType(Object value)
    {
        return new HashSet<>(((List<Object>) super.toTestRowType(value)));
    }

    @Override
    public Object sparkSqlRowValue(GenericInternalRow row, int pos)
    {
        return new HashSet<>(((List<Object>) super.sparkSqlRowValue(row, pos)));
    }

    @Override
    public Object sparkSqlRowValue(Row row, int pos)
    {
        return new HashSet<>(((List<Object>) super.sparkSqlRowValue(row, pos)));
    }

    @Override
    public void setInnerValue(SettableByIndexData<?> udtValue, int pos, Object value)
    {
        udtValue.setSet(pos, (Set<?>) value);
    }

    @Override
    public org.apache.cassandra.spark.shaded.fourzero.datastax.driver.core.DataType driverDataType(boolean isFrozen)
    {
        return org.apache.cassandra.spark.shaded.fourzero.datastax.driver.core.DataType.set(
        ((FourZeroCqlType) type()).driverDataType(isFrozen)
        );
    }

    @Override
    public Object convertForCqlWriter(Object value, CassandraBridge.CassandraVersion version)
    {
        return ((Set<?>) value).stream()
                               .map(o -> type().convertForCqlWriter(o, version))
                               .collect(Collectors.toSet());
    }
}
