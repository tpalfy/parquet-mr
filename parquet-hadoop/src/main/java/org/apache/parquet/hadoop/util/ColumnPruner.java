/*
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
 */
package org.apache.parquet.hadoop.util;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.format.converter.ParquetMetadataConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.metadata.ColumnPath;
import org.apache.parquet.hadoop.metadata.FileMetaData;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ColumnPruner {

  private static final Logger LOG = LoggerFactory.getLogger(ColumnPruner.class);

  public void pruneColumns(Configuration conf, Path inputFile, Path outputFile, List<String> cols) throws IOException {
    Set<ColumnPath> prunePaths = convertToColumnPaths(cols);
    ParquetMetadata pmd = ParquetFileReader.readFooter(conf, inputFile, ParquetMetadataConverter.NO_FILTER);
    FileMetaData metaData = pmd.getFileMetaData();
    MessageType schema = metaData.getSchema();
    List<String> paths = new ArrayList<>();
    getPaths(schema, paths, null);

    for (String col : cols) {
      if (!paths.contains(col)) {
        LOG.warn("Input column name {} doesn't show up in the schema of file {}", col, inputFile.getName());
      }
    }

    ParquetFileWriter writer = new ParquetFileWriter(conf,
      pruneColumnsInSchema(schema, prunePaths), outputFile, ParquetFileWriter.Mode.CREATE);

    writer.start();
    writer.appendFile(HadoopInputFile.fromPath(inputFile, conf));
    writer.end(metaData.getKeyValueMetaData());
  }

  // We have to rewrite getPaths because MessageType only get level 0 paths
  private void getPaths(GroupType schema, List<String> paths, String parent) {
    List<Type> fields = schema.getFields();
    String prefix = (parent == null) ? "" : parent + ".";
    for (Type field : fields) {
      paths.add(prefix + field.getName());
      if (field instanceof GroupType) {
        getPaths(field.asGroupType(), paths, prefix + field.getName());
      }
    }
  }

  private MessageType pruneColumnsInSchema(MessageType schema, Set<ColumnPath> prunePaths) {
    List<Type> fields = schema.getFields();
    List<String> currentPath = new ArrayList<>();
    List<Type> prunedFields = pruneColumnsInFields(fields, currentPath, prunePaths);
    MessageType newSchema = new MessageType(schema.getName(), prunedFields);
    return newSchema;
  }

  private List<Type> pruneColumnsInFields(List<Type> fields, List<String> currentPath, Set<ColumnPath> prunePaths) {
    List<Type> prunedFields = new ArrayList<>();
    for (Type childField : fields) {
      Type prunedChildField = pruneColumnsInField(childField, currentPath, prunePaths);
      if (prunedChildField != null) {
        prunedFields.add(prunedChildField);
      }
    }
    return prunedFields;
  }

  private Type pruneColumnsInField(Type field, List<String> currentPath, Set<ColumnPath> prunePaths) {
    String fieldName = field.getName();
    currentPath.add(fieldName);
    ColumnPath path = ColumnPath.get(currentPath.toArray(new String[0]));
    Type prunedField = null;
    if (!prunePaths.contains(path)) {
      if (field.isPrimitive()) {
        prunedField = field;
      } else {
        List<Type> childFields = ((GroupType) field).getFields();
        List<Type> prunedFields = pruneColumnsInFields(childFields, currentPath, prunePaths);
        if (prunedFields.size() > 0) {
          prunedField = ((GroupType) field).withNewFields(prunedFields);
        }
      }
    }

    currentPath.remove(fieldName);
    return prunedField;
  }

  private Set<ColumnPath> convertToColumnPaths(List<String> cols) {
    Set<ColumnPath> prunePaths = new HashSet<>();
    for (String col : cols) {
      prunePaths.add(ColumnPath.fromDotString(col));
    }
    return prunePaths;
  }
}
