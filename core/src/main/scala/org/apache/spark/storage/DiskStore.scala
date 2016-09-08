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

package org.apache.spark.storage

import java.io.{IOException, File, FileOutputStream, RandomAccessFile}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel.MapMode

import org.apache.spark.Logging
import org.apache.spark.serializer.Serializer
import org.apache.spark.util.Utils

/**
 * Stores BlockManager blocks on disk.
 * 当MemoryStore没有足够空间时,就会使用DiskStore将块存入磁盘
 */
private[spark] class DiskStore(blockManager: BlockManager, diskManager: DiskBlockManager)
  extends BlockStore(blockManager) with Logging {
//对文件进行内存映射的阈值，即当文件大于该值时getBytes方法对文件进行内存映射，而不是直接将该文件的内容读取到字节缓存区。
  val minMemoryMapBytes = blockManager.conf.getSizeAsBytes("spark.storage.memoryMapThreshold", "2m")

  override def getSize(blockId: BlockId): Long = {
    diskManager.getFile(blockId.name).length
  }

  /**
   * 将BlockId对应的字节缓存存储到磁盘
   */
  override def putBytes(blockId: BlockId, _bytes: ByteBuffer, level: StorageLevel): PutResult = {
    // So that we do not modify the input offsets !
    // duplicate does not copy buffer, so inexpensive
    val bytes = _bytes.duplicate()
    logDebug(s"Attempting to put block $blockId")
    val startTime = System.currentTimeMillis
    val file = diskManager.getFile(blockId)//获取文件
    val channel = new FileOutputStream(file).getChannel
    //然后使用NIO的Channel将ByteBuffer写入文件
    Utils.tryWithSafeFinally {
      while (bytes.remaining > 0) {
        channel.write(bytes)
      }
    } {
      channel.close()
    }
    val finishTime = System.currentTimeMillis
    logDebug("Block %s stored as %s file on disk in %d ms".format(
      file.getName, Utils.bytesToString(bytes.limit), finishTime - startTime))
    PutResult(bytes.limit(), Right(bytes.duplicate()))
  }
//将BlockId对应的Array数据存储到磁盘，该方法先将Array序列化，然后存储到相应的文件。
  override def putArray(
      blockId: BlockId,
      values: Array[Any],
      level: StorageLevel,
      returnValues: Boolean): PutResult = {
    putIterator(blockId, values.toIterator, level, returnValues)
  }
//将BlockId对应的Iterator数据存储到磁盘，该方法先将Iterator序列化，然后存储到相应的文件。
  override def putIterator(
      blockId: BlockId,
      values: Iterator[Any],
      level: StorageLevel,
      returnValues: Boolean): PutResult = {

    logDebug(s"Attempting to write values for block $blockId")
    val startTime = System.currentTimeMillis
    //使用diskManager.getFile获取blockId对应的block文件,并封装为FileOutputStream

    val file = diskManager.getFile(blockId)
    val outputStream = new FileOutputStream(file)
    try {
      Utils.tryWithSafeFinally {
        //使用dataSerializeStream方法,将FileOutputStrem序列化并压缩
        blockManager.dataSerializeStream(blockId, outputStream, values)
      } {
        // Close outputStream here because it should be closed before file is deleted.        
        outputStream.close()
      }
    } catch {
      case e: Throwable =>
        if (file.exists()) {
          file.delete()
        }
        throw e
    }

    val length = file.length

    val timeTaken = System.currentTimeMillis - startTime
    logDebug("Block %s stored as %s file on disk in %d ms".format(
      file.getName, Utils.bytesToString(length), timeTaken))
    //如果需要返回写入的数据(即returnValue),则将写入的文件使用getBytes读取
    //与文件的长度一并封装到PutResult中并返回
    if (returnValues) {
      // Return a byte buffer for the contents of the file
      val buffer = getBytes(blockId).get
      PutResult(length, Right(buffer))
    } else {
      //只返回文件长度
      PutResult(length, null)
    }
  }
   /**
    * 读取文件中偏移为offset，长度为length的内容。
    * 该方法会判断length是否大于minMemoryMapBytes，若大于，则做内存映射，否则直接读取到字节缓存中。
    */
  private def getBytes(file: File, offset: Long, length: Long): Option[ByteBuffer] = {
    val channel = new RandomAccessFile(file, "r").getChannel
    Utils.tryWithSafeFinally {
      // For small files, directly read rather than memory map
      if (length < minMemoryMapBytes) {
        /**
         * 从FileChannel读取数据
         * 1)首先，allocate分配一个Buffer,从FileChannel中读取的数据将被读到Buffer中
         * 2)调用FileChannel.read()方法。该方法将数据从FileChannel读取到Buffer中。
         *   read()方法返回的int值表示了有多少字节被读到了Buffer中。如果返回-1，表示到了文件末尾。
         */
        val buf = ByteBuffer.allocate(length.toInt) //分配块缓冲区
        channel.position(offset)//位置
        while (buf.remaining() != 0) {//剩余
          if (channel.read(buf) == -1) {
            throw new IOException("Reached EOF before filling buffer\n" +
              s"offset=$offset\nfile=${file.getAbsolutePath}\nbuf.remaining=${buf.remaining}")
          }
        }
        buf.flip() //反转此缓冲区
        Some(buf)
      } else {
        Some(channel.map(MapMode.READ_ONLY, offset, length))
      }
    } {
      channel.close()
    }
  }
  /**
   *读取存储在磁盘中与BlockId对应的内容。
   */
  override def getBytes(blockId: BlockId): Option[ByteBuffer] = {
    val file = diskManager.getFile(blockId.name)
    getBytes(file, 0, file.length)
  }
  //根据FileSegment读取内容，其中 FileSegment存放文件和要读取数据的偏移和大小
  def getBytes(segment: FileSegment): Option[ByteBuffer] = {
    getBytes(segment.file, segment.offset, segment.length)
  }
//读取BlockId对应的内容，并反序列化为Iterator
  override def getValues(blockId: BlockId): Option[Iterator[Any]] = {
    getBytes(blockId).map(buffer => blockManager.dataDeserialize(blockId, buffer))
  }

  /**
   * A version of getValues that allows a custom serializer. This is used as part of the
   * shuffle short-circuit code.
   * 读取BlockId对应的内容，并根据自定义的Serializer反序列化为Iterator。
   */
  def getValues(blockId: BlockId, serializer: Serializer): Option[Iterator[Any]] = {
    // TODO: Should bypass getBytes and use a stream based implementation, so that
    // we won't use a lot of memory during e.g. external sort merge.
    getBytes(blockId).map(bytes => blockManager.dataDeserialize(blockId, bytes, serializer))
  }
  //删除存储的BlockId对应的Block。
  override def remove(blockId: BlockId): Boolean = {
    val file = diskManager.getFile(blockId.name)
    // If consolidation mode is used With HashShuffleMananger, the physical filename for the block
    // is different from blockId.name. So the file returns here will not be exist, thus we avoid to
    // delete the whole consolidated file by mistake.
    if (file.exists()) {
      file.delete()
    } else {
      false
    }
  }
  //判断是否存储BlockId对应的Block。
  override def contains(blockId: BlockId): Boolean = {
    val file = diskManager.getFile(blockId.name)
    file.exists()
  }
}
