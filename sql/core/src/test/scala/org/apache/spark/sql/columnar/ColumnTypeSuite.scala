/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.columnar

import java.nio.ByteBuffer

import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, Serializer}

import org.apache.spark.{Logging, SparkConf, SparkFunSuite}
import org.apache.spark.serializer.KryoRegistrator
import org.apache.spark.sql.catalyst.expressions.GenericMutableRow
import org.apache.spark.sql.columnar.ColumnarTestUtils._
import org.apache.spark.sql.execution.SparkSqlSerializer
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String


class ColumnTypeSuite extends SparkFunSuite with Logging {
  val DEFAULT_BUFFER_SIZE = 512

  test("defaultSize") {
    val checks = Map(
      INT -> 4, SHORT -> 2, LONG -> 8, BYTE -> 1, DOUBLE -> 8, FLOAT -> 4,
      FIXED_DECIMAL(15, 10) -> 8, BOOLEAN -> 1, STRING -> 8, DATE -> 4, TIMESTAMP -> 8,
      BINARY -> 16, GENERIC -> 16)

    checks.foreach { case (columnType, expectedSize) =>
      assertResult(expectedSize, s"Wrong defaultSize for $columnType") {
        columnType.defaultSize
      }
    }
  }

  test("actualSize") {
    def checkActualSize[T <: DataType, JvmType](
        columnType: ColumnType[T, JvmType],
        value: JvmType,
        expected: Int): Unit = {

      assertResult(expected, s"Wrong actualSize for $columnType") {
        val row = new GenericMutableRow(1)
        columnType.setField(row, 0, value)
        columnType.actualSize(row, 0)
      }
    }

    checkActualSize(INT, Int.MaxValue, 4)
    checkActualSize(SHORT, Short.MaxValue, 2)
    checkActualSize(LONG, Long.MaxValue, 8)
    checkActualSize(BYTE, Byte.MaxValue, 1)
    checkActualSize(DOUBLE, Double.MaxValue, 8)
    checkActualSize(FLOAT, Float.MaxValue, 4)
    checkActualSize(FIXED_DECIMAL(15, 10), Decimal(0, 15, 10), 8)
    checkActualSize(BOOLEAN, true, 1)
    checkActualSize(STRING, UTF8String.fromString("hello"), 4 + "hello".getBytes("utf-8").length)
    checkActualSize(DATE, 0, 4)
    checkActualSize(TIMESTAMP, 0L, 8)

    val binary = Array.fill[Byte](4)(0: Byte)
    checkActualSize(BINARY, binary, 4 + 4)

    val generic = Map(1 -> "a")
    checkActualSize(GENERIC, SparkSqlSerializer.serialize(generic), 4 + 8)
  }

  testNativeColumnType[BooleanType.type](
    BOOLEAN,
    (buffer: ByteBuffer, v: Boolean) => {
      buffer.put((if (v) 1 else 0).toByte)
    },
    (buffer: ByteBuffer) => {
      buffer.get() == 1
    })

  testNativeColumnType[IntegerType.type](INT, _.putInt(_), _.getInt)

  testNativeColumnType[ShortType.type](SHORT, _.putShort(_), _.getShort)

  testNativeColumnType[LongType.type](LONG, _.putLong(_), _.getLong)

  testNativeColumnType[ByteType.type](BYTE, _.put(_), _.get)

  testNativeColumnType[DoubleType.type](DOUBLE, _.putDouble(_), _.getDouble)

  testNativeColumnType[DecimalType](
    FIXED_DECIMAL(15, 10),
    (buffer: ByteBuffer, decimal: Decimal) => {
      buffer.putLong(decimal.toUnscaledLong)
    },
    (buffer: ByteBuffer) => {
      Decimal(buffer.getLong(), 15, 10)
    })

  testNativeColumnType[FloatType.type](FLOAT, _.putFloat(_), _.getFloat)

  testNativeColumnType[StringType.type](
    STRING,
    (buffer: ByteBuffer, string: UTF8String) => {
      val bytes = string.getBytes
      buffer.putInt(bytes.length)
      buffer.put(bytes)
    },
    (buffer: ByteBuffer) => {
      val length = buffer.getInt()
      val bytes = new Array[Byte](length)
      buffer.get(bytes)
      UTF8String.fromBytes(bytes)
    })

  testColumnType[BinaryType.type, Array[Byte]](
    BINARY,
    (buffer: ByteBuffer, bytes: Array[Byte]) => {
      buffer.putInt(bytes.length).put(bytes)
    },
    (buffer: ByteBuffer) => {
      val length = buffer.getInt()
      val bytes = new Array[Byte](length)
      buffer.get(bytes, 0, length)
      bytes
    })

  test("GENERIC") {
    val buffer = ByteBuffer.allocate(512)
    val obj = Map(1 -> "spark", 2 -> "sql")
    val serializedObj = SparkSqlSerializer.serialize(obj)

    GENERIC.append(SparkSqlSerializer.serialize(obj), buffer)
    buffer.rewind()

    val length = buffer.getInt()
    assert(length === serializedObj.length)

    assertResult(obj, "Deserialized object didn't equal to the original object") {
      val bytes = new Array[Byte](length)
      buffer.get(bytes, 0, length)
      SparkSqlSerializer.deserialize(bytes)
    }

    buffer.rewind()
    buffer.putInt(serializedObj.length).put(serializedObj)

    assertResult(obj, "Deserialized object didn't equal to the original object") {
      buffer.rewind()
      SparkSqlSerializer.deserialize(GENERIC.extract(buffer))
    }
  }

  test("CUSTOM") {
    val conf = new SparkConf()
    conf.set("spark.kryo.registrator", "org.apache.spark.sql.columnar.Registrator")
    val serializer = new SparkSqlSerializer(conf).newInstance()

    val buffer = ByteBuffer.allocate(512)
    val obj = CustomClass(Int.MaxValue, Long.MaxValue)
    val serializedObj = serializer.serialize(obj).array()

    GENERIC.append(serializer.serialize(obj).array(), buffer)
    buffer.rewind()

    val length = buffer.getInt
    assert(length === serializedObj.length)
    assert(13 == length) // id (1) + int (4) + long (8)

    val genericSerializedObj = SparkSqlSerializer.serialize(obj)
    assert(length != genericSerializedObj.length)
    assert(length < genericSerializedObj.length)

    assertResult(obj, "Custom deserialized object didn't equal the original object") {
      val bytes = new Array[Byte](length)
      buffer.get(bytes, 0, length)
      serializer.deserialize(ByteBuffer.wrap(bytes))
    }

    buffer.rewind()
    buffer.putInt(serializedObj.length).put(serializedObj)

    assertResult(obj, "Custom deserialized object didn't equal the original object") {
      buffer.rewind()
      serializer.deserialize(ByteBuffer.wrap(GENERIC.extract(buffer)))
    }
  }

  def testNativeColumnType[T <: AtomicType](
      columnType: NativeColumnType[T],
      putter: (ByteBuffer, T#InternalType) => Unit,
      getter: (ByteBuffer) => T#InternalType): Unit = {

    testColumnType[T, T#InternalType](columnType, putter, getter)
  }

  def testColumnType[T <: DataType, JvmType](
      columnType: ColumnType[T, JvmType],
      putter: (ByteBuffer, JvmType) => Unit,
      getter: (ByteBuffer) => JvmType): Unit = {

    val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
    val seq = (0 until 4).map(_ => makeRandomValue(columnType))

    test(s"$columnType.extract") {
      buffer.rewind()
      seq.foreach(putter(buffer, _))

      buffer.rewind()
      seq.foreach { expected =>
        logInfo("buffer = " + buffer + ", expected = " + expected)
        val extracted = columnType.extract(buffer)
        assert(
          expected === extracted,
          "Extracted value didn't equal to the original one. " +
            hexDump(expected) + " != " + hexDump(extracted) +
            ", buffer = " + dumpBuffer(buffer.duplicate().rewind().asInstanceOf[ByteBuffer]))
      }
    }

    test(s"$columnType.append") {
      buffer.rewind()
      seq.foreach(columnType.append(_, buffer))

      buffer.rewind()
      seq.foreach { expected =>
        assert(
          expected === getter(buffer),
          "Extracted value didn't equal to the original one")
      }
    }
  }

  private def hexDump(value: Any): String = {
    value.toString.map(ch => Integer.toHexString(ch & 0xffff)).mkString(" ")
  }

  private def dumpBuffer(buff: ByteBuffer): Any = {
    val sb = new StringBuilder()
    while (buff.hasRemaining) {
      val b = buff.get()
      sb.append(Integer.toHexString(b & 0xff)).append(' ')
    }
    if (sb.nonEmpty) sb.setLength(sb.length - 1)
    sb.toString()
  }

  test("column type for decimal types with different precision") {
    (1 to 18).foreach { i =>
      assertResult(FIXED_DECIMAL(i, 0)) {
        ColumnType(DecimalType(i, 0))
      }
    }

    assertResult(GENERIC) {
      ColumnType(DecimalType(19, 0))
    }
  }
}

private[columnar] final case class CustomClass(a: Int, b: Long)

private[columnar] object CustomerSerializer extends Serializer[CustomClass] {
  override def write(kryo: Kryo, output: Output, t: CustomClass) {
    output.writeInt(t.a)
    output.writeLong(t.b)
  }
  override def read(kryo: Kryo, input: Input, aClass: Class[CustomClass]): CustomClass = {
    val a = input.readInt()
    val b = input.readLong()
    CustomClass(a, b)
  }
}

private[columnar] final class Registrator extends KryoRegistrator {
  override def registerClasses(kryo: Kryo) {
    kryo.register(classOf[CustomClass], CustomerSerializer)
  }
}
