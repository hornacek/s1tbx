/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package org.esa.s1tbx;

import org.esa.beam.util.PropertyMap;
import org.esa.snap.util.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Configuration for automated test
 */
public class TestConfig {

    private final String name;
    private final Properties prop;
    private final List<TestInfo> testList = new ArrayList<TestInfo>(20);
    private int maxProductsPerInputFolder = -1;

    public TestConfig(final String name) throws Exception {
        this.name = name;
        final PropertyMap propMap = Config.getAutomatedTestConfigPropertyMap(name);
        if (propMap == null)
            throw new Exception("Test config " + name + " not found");

        prop = propMap.getProperties();
        importTests();
    }

    public List<TestInfo> getTestList() {
        return testList;
    }

    public int getMaxProductsPerInputFolder() {
        return maxProductsPerInputFolder;
    }

    private void importTests() throws Exception {
        final String prefix = "test.";

        String maxIn = readProp("maxProductsPerInputFolder");
        if (maxIn != null) {
            maxProductsPerInputFolder = Integer.parseInt(maxIn);
        }

        final int numProperties = prop.size() / 4;
        for (int i = 0; i <= numProperties; ++i) {
            final String key = prefix + i;
            final String graph = readProp(key + ".graph");
            if (graph != null) {
                final String skip = readProp(key + ".skip");
                if (skip != null && skip.equalsIgnoreCase("true")) {
                    System.out.println(name + ": " + key + " skipped");
                    continue;
                }

                final String input_products = readProp(key + ".input_products");
                final String expected_results = readProp(key + ".expected_results") + '\\' + name + "\\test" + i;
                final String output_products = readProp(key + ".output_products") + '\\' + name + "\\test" + i;

                if (input_products == null || output_products == null) {
                    throw new Exception("Test configuration " + key + " is incomplete");
                }

                final TestInfo test = new TestInfo(i, graph, input_products, expected_results, output_products);
                if (!test.graphFile.exists())
                    throw new Exception(test.graphFile.getAbsolutePath() + " does not exist for " + key);
                if (!test.inputFolder.exists())
                    throw new Exception(test.inputFolder.getAbsolutePath() + " does not exist for " + key);

                testList.add(test);
            }
        }
    }

    private String readProp(final String tag) {
        String val = prop.getProperty(tag);
        if (val != null && val.contains("${")) {
            val = resolve(val);
        }

        return val;
    }

    private String resolve(String value) {
        final int idx1 = value.indexOf("${");
        final int idx2 = value.indexOf('}') + 1;
        final String keyWord = value.substring(idx1 + 2, idx2 - 1);
        final String fullKey = value.substring(idx1, idx2);

        final String property = prop.getProperty(keyWord);
        if (property != null && property.length() > 0) {
            value = value.replace(fullKey, property);
        }

        if (value.contains("${"))
            value = resolve(value);

        return value;
    }
}
