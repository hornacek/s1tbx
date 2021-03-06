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
package org.esa.nest.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.MetadataElement;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.snap.datamodel.AbstractMetadata;
import org.esa.snap.util.TestData;
import org.esa.snap.util.TestUtils;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit test for MultilookOperator.
 */
public class TestCreateElevationOp {

    static {
        TestUtils.initTestEnvironment();
    }

    private final static OperatorSpi spi = new CreateElevationOp.Spi();

    private static double[] expectedValues = {
            1526.91650390625, 1527.80908203125, 1536.530029296875, 1548.84130859375,
            1525.94140625, 1520.9620361328125, 1530.3487548828125, 1552.3184814453125
    };

    /**
     * Processes a product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testProcessing() throws Exception {
        final File inputFile = TestData.inputASAR_WSM;
        if (!inputFile.exists()) {
            TestUtils.skipTest(this, inputFile + " not found");
            return;
        }
        final Product sourceProduct = TestUtils.readSourceProduct(inputFile);

        final CreateElevationOp op = (CreateElevationOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true, true);

        final Band elevBand = targetProduct.getBand("elevation");
        assertNotNull(elevBand);

        final double[] demValues = new double[8];
        elevBand.readPixels(0, 0, 4, 2, demValues, ProgressMonitor.NULL);

        assertTrue(Arrays.equals(expectedValues, demValues));

        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(targetProduct);
        //TestUtils.attributeEquals(abs, AbstractMetadata.DEM, "SRTM");
    }
}
