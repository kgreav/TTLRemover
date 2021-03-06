/*
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
package org.apache.cassandra.noTTL;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.*;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.ISSTableScanner;
import org.apache.cassandra.io.sstable.KeyIterator;
import org.apache.cassandra.io.sstable.format.SSTableWriter;
import org.apache.cassandra.service.ActiveRepairService;
import org.apache.cassandra.tools.Util;
import org.apache.cassandra.utils.JVMStabilityInspector;
import org.apache.commons.cli.*;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

/**
 * Do batch TTL removing on table
 */
public class TTLRemover {
    private static CommandLine cmd;
    private static Options options = new Options();;
    private static final String OUTPUT_PATH = "p";

    static
    {
        Option outputPath = new Option(OUTPUT_PATH, true, "Output path, end with '/'");
        options.addOption(outputPath);
    }


    private static void stream(Descriptor descriptor, String toSSTable) throws IOException {
        long keyCount = countKeys(descriptor);

        NoTTLReader noTTLreader = NoTTLReader.open(descriptor);

        ISSTableScanner noTTLscanner = noTTLreader.getScanner();

        ColumnFamily columnFamily = ArrayBackedSortedColumns.factory.create(descriptor.ksname, descriptor.cfname);
        SSTableWriter writer = SSTableWriter.create(Descriptor.fromFilename(toSSTable), keyCount, ActiveRepairService.UNREPAIRED_SSTABLE,0);

        NoTTLSSTableIdentityIterator row;

        try
        {
            while (noTTLscanner.hasNext()) //read data from disk //NoTTLBigTableScanner
            {
                row = (NoTTLSSTableIdentityIterator) noTTLscanner.next();
                serializeRow(row,columnFamily, row.getColumnFamily().metadata());
                writer.append(row.getKey(), columnFamily);
                columnFamily.clear();
            }

            writer.finish(true);

        }
        finally
        {
            noTTLscanner.close();
        }

    }

    private static void serializeRow(Iterator<OnDiskAtom> atoms, ColumnFamily columnFamily, CFMetaData metadata) {

        while (atoms.hasNext())
        {
            serializeAtom(atoms.next(), metadata, columnFamily);
        }

    }

    private static void serializeAtom(OnDiskAtom atom, CFMetaData metadata, ColumnFamily columnFamily) {
        if (atom instanceof Cell)
        {
            Cell cell = (Cell) atom;
            if (cell instanceof BufferExpiringCell)
            {
                columnFamily.addColumn(cell.name(),cell.value(),cell.timestamp());
            }
            else if (cell instanceof BufferDeletedCell)
            {

            }
            else
                columnFamily.addColumn(cell);

        }
    }

    private static long countKeys(Descriptor descriptor) {
        KeyIterator iter = new KeyIterator(descriptor);
        long keycount = 0;
        try
        {
            while (iter.hasNext())
            {
                iter.next();
                keycount++;
            }

        }
        finally
        {
            iter.close();
        }
        System.out.println("Key count: "+keycount);
        return keycount;
    }

    public static void main(String[] args) throws ConfigurationException
    {
        CommandLineParser parser = new PosixParser();

        try
        {
            cmd = parser.parse(options,args);
        }
        catch (ParseException e)
        {
            System.err.println(e.getMessage());
            printUsage();
            System.exit(1);
        }

        if (cmd.getArgs().length != 1)
        {
            printUsage();
            System.exit(1);
        }

        String fromSSTable = new File(cmd.getArgs()[0]).getAbsolutePath();
        String toSSTable = new File(cmd.getArgs()[0]).getName();

        Util.initDatabaseDescriptor();

        Schema.instance.loadFromDisk(false);  //load kspace "systemcf" and its tables;
        Keyspace.setInitialized();

        Descriptor descriptor = Descriptor.fromFilename(fromSSTable);

        if (Schema.instance.getKSMetaData(descriptor.ksname) == null)
        {
            System.err.println(String.format("Filename %s references to nonexistent keyspace: %s!",fromSSTable, descriptor.ksname));
            System.exit(1);
        }

        Keyspace keyspace = Keyspace.open(descriptor.ksname); //load customised keyspace

        ColumnFamilyStore cfStore = null;

        try
        {
            cfStore = keyspace.getColumnFamilyStore(descriptor.cfname);
        }
        catch (IllegalArgumentException e)
        {
            System.err.println(String.format("The provided column family is not part of this cassandra keyspace: keyspace = %s, column family = %s",
                    descriptor.ksname, descriptor.cfname));
            System.exit(1);
        }

        try
        {
            if(cmd.hasOption(OUTPUT_PATH))
            {
                String outputFolder = cmd.getOptionValue(OUTPUT_PATH);
                String toSSTableDir = outputFolder+descriptor.ksname+"/"+descriptor.cfname;
                File directory = new File(toSSTableDir);
                directory.mkdirs();
                toSSTable = toSSTableDir+"/"+toSSTable;
                stream(descriptor, toSSTable);
            }
            else {
                printUsage();
                System.exit(1);
            }
        }
        catch (Exception e) {
            JVMStabilityInspector.inspectThrowable(e);
            e.printStackTrace();
            System.err.println("ERROR: " + e.getMessage());
            System.exit(-1);
        }

        System.exit(0);
    }

    private static void printUsage()
    {
        System.out.printf("Usage: %s <target sstable> -p <output path>",TTLRemover.class.getName());
    }

}
