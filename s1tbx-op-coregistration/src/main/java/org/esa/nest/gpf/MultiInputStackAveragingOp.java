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

import org.esa.beam.framework.datamodel.*;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.*;
import org.esa.beam.util.ProductUtils;
import org.esa.snap.datamodel.AbstractMetadata;
import org.esa.snap.gpf.OperatorUtils;
import org.esa.snap.gpf.StackUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Averaging multi-temporal images
 */
@OperatorMetadata(alias = "Multi-Input-Stack-Averaging",
        category = "SAR Processing/Coregistration",
        authors = "Jun Lu, Luis Veci",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        description = "Averaging multi-temporal images")
public class MultiInputStackAveragingOp extends Operator {


    @SourceProducts
    private Product[] sourceProducts;

    @TargetProduct
    private Product targetProduct;

    @Parameter(valueSet = {"Mean Average", "Minimum", "Maximum", "Standard Deviation", "Coefficient of Variation"},
            defaultValue = "Mean Average", label = "Statistic")
    private String statistic = "Mean Average";

    private BandInfo[] nameGroups;
    private Product sourceProduct = null;

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link org.esa.beam.framework.datamodel.Product} annotated with the
     * {@link org.esa.beam.framework.gpf.annotations.TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws org.esa.beam.framework.gpf.OperatorException If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {

        try {
            validateSourceProducts();

            createSourceProductStack();

            targetProduct = new Product(sourceProduct.getName(),
                    sourceProduct.getProductType(),
                    sourceProduct.getSceneRasterWidth(),
                    sourceProduct.getSceneRasterHeight());

            ProductUtils.copyProductNodes(sourceProduct, targetProduct);

            nameGroups = getBandGroupNames();

            for (BandInfo bandInfo : nameGroups) {
                if (bandInfo.isVirtual) {
                    // add virtual intensity bands
                    addOriginalVirtualBands(bandInfo.name);
                } else {
                    final String name_prefix = bandInfo.name;
                    final Band[] sourceBands = getSourceBands(name_prefix);
                    final String unit = sourceBands[0].getUnit();
                    final double nodatavalue = sourceBands[0].getNoDataValue();

                    switch (statistic) {
                        case "Mean Average":
                            addVirtualBand("average", name_prefix, mean(sourceBands), unit, nodatavalue);
                            break;
                        case "Minimum":
                            addVirtualBand("min", name_prefix, min(sourceBands), unit, nodatavalue);
                            break;
                        case "Maximum":
                            addVirtualBand("max", name_prefix, max(sourceBands), unit, nodatavalue);
                            break;
                        case "Standard Deviation":
                            addVirtualBand("stddev", name_prefix, stddev(sourceBands), unit, nodatavalue);
                            break;
                        case "Coefficient of Variation":
                            addVirtualBand("coefVar", name_prefix, coefVar(sourceBands), unit, nodatavalue);
                            break;
                    }
                }
            }

            updateMetadata(targetProduct);
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    @Override
    public void dispose() {
        for (BandInfo bandInfo : nameGroups) {
            final Band srcBand = sourceProduct.getBand(bandInfo.name);
            if (srcBand != null) {
                sourceProduct.removeBand(srcBand);
            }
        }
        sourceProduct.setModified(false);
    }

    private void validateSourceProducts() {

        if (sourceProducts.length < 2) {
            throw new OperatorException("Please select at least two source products");
        }

        final int width = sourceProducts[0].getSceneRasterWidth();
        final int height = sourceProducts[0].getSceneRasterHeight();

        for (int i = 1; i < sourceProducts.length; i++) {
            if (sourceProducts[i].getSceneRasterWidth() != width ||
                    sourceProducts[i].getSceneRasterHeight() != height) {
                throw new OperatorException("Please select source products of the same dimension");
            }
        }
    }

    private void createSourceProductStack() {

        sourceProduct = new Product(sourceProducts[0].getName(),
                sourceProducts[0].getProductType(),
                sourceProducts[0].getSceneRasterWidth(),
                sourceProducts[0].getSceneRasterHeight());

        ProductUtils.copyProductNodes(sourceProducts[0], sourceProduct);

        int prodIdx = 0;
        for (Product product:sourceProducts) {
            final Band[] sourceBands = product.getBands();
            for (Band band:sourceBands) {
                final String srcBandName = band.getName();
                final String srcBandNameWithTimeStamp = srcBandName + "_" + String.valueOf(prodIdx);
                ProductUtils.copyBand(srcBandName, product, srcBandNameWithTimeStamp, sourceProduct, true);
            }
            prodIdx++;
        }
    }

    private void updateMetadata(final Product targetProduct) {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.coregistered_stack, 0);
    }

    private BandInfo[] getBandGroupNames() {

        final Band[] bands = sourceProduct.getBands();
        final Set<String> nameSet = new LinkedHashSet<>();
        final List<BandInfo> bandGroup = new ArrayList<>();
        for (Band band : bands) {
            final String name = StackUtils.getBandNameWithoutDate(band.getName());
            if (!nameSet.contains(name)) {
                nameSet.add(name);
                bandGroup.add(new BandInfo(band, name));
            }
        }
        return bandGroup.toArray(new BandInfo[bandGroup.size()]);
    }

    private Band[] getSourceBands(final String name_prefix) {
        final Band[] bands = sourceProduct.getBands();
        final List<Band> bandList = new ArrayList<>();
        for (Band band : bands) {
            if (!(band instanceof VirtualBand) && band.getName().startsWith(name_prefix)) {
                bandList.add(band);
            }
        }
        return bandList.toArray(new Band[bandList.size()]);
    }

    private void addVirtualBand(final String operation, final String name_prefix, final String expression,
                                final String unit, final double nodatavalue) {
        final VirtualBand virtBand = new VirtualBand(name_prefix,
                ProductData.TYPE_FLOAT32,
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight(),
                expression);
        virtBand.setUnit(unit);
        virtBand.setDescription(name_prefix + ' ' + operation + ' ' + unit);
        virtBand.setNoDataValueUsed(true);
        virtBand.setNoDataValue(nodatavalue);

        final Band srcBand = sourceProduct.getBand(virtBand.getName());
        if (srcBand != null) {
            sourceProduct.removeBand(srcBand);
        }
        sourceProduct.addBand(virtBand);

        ProductUtils.copyBand(name_prefix, sourceProduct, targetProduct, true);
    }

    private void addOriginalVirtualBands(final String trgBandName) {
        final Band[] srcBands = sourceProduct.getBands();
        Band virtSrcBand = null;
        for (Band band : srcBands) {
            if (band.getName().startsWith(trgBandName) && band instanceof VirtualBand) {
                virtSrcBand = band;
                break;
            }
        }
        if (virtSrcBand == null)
            return;

        final VirtualBand srcBand = (VirtualBand) virtSrcBand;
        String expression = srcBand.getExpression();

        for (Band b : srcBands) {
            final String bName = b.getName();
            if (expression.contains(bName) && !nameGroupContains(bName)) {
                final String newName = StackUtils.getBandNameWithoutDate(bName);
                expression = expression.replaceAll(bName, newName);
            }
        }

        final VirtualBand virtBand = new VirtualBand(trgBandName,
                srcBand.getDataType(),
                srcBand.getSceneRasterWidth(),
                srcBand.getSceneRasterHeight(),
                expression);
        virtBand.setUnit(srcBand.getUnit());
        virtBand.setDescription(srcBand.getDescription());
        virtBand.setNoDataValue(srcBand.getNoDataValue());
        virtBand.setNoDataValueUsed(srcBand.isNoDataValueUsed());
        targetProduct.addBand(virtBand);
    }

    private boolean nameGroupContains(final String name) {
        for(BandInfo b : nameGroups) {
            if(name.equals(b.name))
                return true;
        }
        return false;
    }

    private static String mean(final Band[] sourceBands) {
        final StringBuilder expression = new StringBuilder("( ");
        int cnt = 0;
        for (Band band : sourceBands) {
            if (cnt > 0)
                expression.append(" + ");
            expression.append(band.getName());
            ++cnt;
        }
        expression.append(") / ");
        expression.append(sourceBands.length);

        return expression.toString();
    }

    private static String min(final Band[] sourceBands) {
        final StringBuilder expression = new StringBuilder("min( ");
        int cnt = 0;
        for (Band band : sourceBands) {
            if (cnt > 0) {
                expression.append(", ");
                if (cnt < sourceBands.length - 1)
                    expression.append("min( ");
            }
            expression.append(band.getName());
            ++cnt;
        }
        for (int i = 0; i < sourceBands.length - 1; ++i) {
            expression.append(")");
        }

        return expression.toString();
    }

    private static String max(final Band[] sourceBands) {
        final StringBuilder expression = new StringBuilder("max( ");
        int cnt = 0;
        for (Band band : sourceBands) {
            if (cnt > 0) {
                expression.append(", ");
                if (cnt < sourceBands.length - 1)
                    expression.append("max( ");
            }
            expression.append(band.getName());
            ++cnt;
        }
        for (int i = 0; i < sourceBands.length - 1; ++i) {
            expression.append(")");
        }

        return expression.toString();
    }

    private static String mean2(final Band[] sourceBands) {
        final StringBuilder expression = new StringBuilder("( ");
        int cnt = 0;
        for (Band band : sourceBands) {
            if (cnt > 0)
                expression.append(" + ");
            expression.append("sqr(");
            expression.append(band.getName());
            expression.append(")");
            ++cnt;
        }
        expression.append(") / ");
        expression.append(sourceBands.length);

        return expression.toString();
    }

    private static String mean4(final Band[] sourceBands) {
        final StringBuilder expression = new StringBuilder("( ");
        int cnt = 0;
        for (Band band : sourceBands) {
            if (cnt > 0)
                expression.append(" + ");
            expression.append("pow(");
            expression.append(band.getName());
            expression.append(", 4)");
            ++cnt;
        }
        expression.append(") / ");
        expression.append(sourceBands.length);

        return expression.toString();
    }

    private static String stddev(final Band[] sourceBands) {
        return "sqrt( " + mean2(sourceBands) + " - " + "sqr(" + mean(sourceBands) + "))";
    }

    private static String coefVar(final Band[] sourceBands) {
        final String m2 = mean2(sourceBands);
        return "sqrt( " + mean4(sourceBands) + " - " + "sqr(" + m2 + ")) / " + m2;
    }

    private static class BandInfo {
        final String name;
        final boolean isVirtual;

        public BandInfo(final Band band, final String name) {
            this.name = name;
            this.isVirtual = band instanceof VirtualBand;
        }
    }

    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.beam.framework.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see org.esa.beam.framework.gpf.OperatorSpi#createOperator()
     * @see org.esa.beam.framework.gpf.OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(MultiInputStackAveragingOp.class);
        }
    }
}
