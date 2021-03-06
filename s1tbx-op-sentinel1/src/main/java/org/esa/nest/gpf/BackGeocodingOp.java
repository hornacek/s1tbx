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
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import org.esa.beam.framework.datamodel.*;
import org.esa.nest.dataio.dem.ElevationModel;
import org.esa.beam.framework.dataop.resamp.Resampling;
import org.esa.beam.framework.dataop.resamp.ResamplingFactory;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.*;
import org.esa.beam.util.ProductUtils;
import org.esa.nest.dataio.dem.DEMFactory;
import org.esa.nest.dataio.dem.FileElevationModel;
import org.esa.snap.datamodel.AbstractMetadata;
import org.esa.snap.datamodel.ProductInformation;
import org.esa.snap.datamodel.Unit;
import org.esa.snap.eo.Constants;
import org.esa.snap.eo.GeoUtils;
import org.esa.nest.gpf.geometric.SARGeocoding;
import org.esa.snap.gpf.OperatorUtils;
import org.esa.snap.gpf.ReaderUtils;
import org.esa.snap.gpf.StackUtils;
import org.esa.snap.gpf.TileIndex;
import org.jlinda.core.delaunay.FastDelaunayTriangulator;
import org.jlinda.core.delaunay.Triangle;
import org.jlinda.core.delaunay.TriangulationException;


import java.awt.*;
import java.io.File;
import java.util.*;

/**
 * "Backgeocoding" + "Coregistration" processing blocks in The Sentinel-1 TOPS InSAR processing chain.
 * Burst co-registration is performed using orbits and DEM.
 */
@OperatorMetadata(alias = "Back-Geocoding",
        category = "SAR Processing/SENTINEL-1",
        authors = "Jun Lu, Luis Veci",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        description = "Bursts co-registration using orbit and DEM")
public final class BackGeocodingOp extends Operator {

    @SourceProducts
    private Product[] sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(valueSet = {"ACE", "GETASSE30", "SRTM 3Sec", "ASTER 1sec GDEM"},
            description = "The digital elevation model.",
            defaultValue = "SRTM 3Sec", label = "Digital Elevation Model")
    private String demName = "SRTM 3Sec";

    @Parameter(valueSet = {ResamplingFactory.NEAREST_NEIGHBOUR_NAME,
            ResamplingFactory.BILINEAR_INTERPOLATION_NAME,
            ResamplingFactory.CUBIC_CONVOLUTION_NAME,
            ResamplingFactory.BICUBIC_INTERPOLATION_NAME,
            ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME},
            defaultValue = ResamplingFactory.BICUBIC_INTERPOLATION_NAME,
            label = "DEM Resampling Method")
    private String demResamplingMethod = ResamplingFactory.BICUBIC_INTERPOLATION_NAME;

    @Parameter(label = "External DEM")
    private File externalDEMFile = null;

    @Parameter(label = "DEM No Data Value", defaultValue = "0")
    private double externalDEMNoDataValue = 0;

    @Parameter(valueSet = {ResamplingFactory.BILINEAR_INTERPOLATION_NAME,
            ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME,
            ResamplingFactory.BISINC_21_POINT_INTERPOLATION_NAME},
            defaultValue = ResamplingFactory.BISINC_21_POINT_INTERPOLATION_NAME,
            description = "The method to be used when resampling the slave grid onto the master grid.",
            label = "Resampling Type")
    private String resamplingType = ResamplingFactory.BISINC_21_POINT_INTERPOLATION_NAME;

    @Parameter(defaultValue = "false", label = "Output Range and Azimuth Offset")
    private boolean outputRangeAzimuthOffset = false;

    @Parameter(defaultValue = "false", label = "Output Deramp Phase")
    private boolean outputDerampPhase = false;

    private Resampling selectedResampling = null;

    private Product masterProduct = null;
    private Product slaveProduct = null;

    private Sentinel1Utils mSU = null;
    private Sentinel1Utils sSU = null;
    private Sentinel1Utils.SubSwathInfo[] mSubSwath = null;
    private Sentinel1Utils.SubSwathInfo[] sSubSwath = null;

    private int numOfSubSwath = 0;
    private String acquisitionMode = null;
    private ElevationModel dem = null;
    private boolean isElevationModelAvailable = false;
    private double demNoDataValue = 0; // no data value for DEM

    private double noDataValue = 0.0;
    
	private int subSwathIndex = 0;
    private String swathIndexStr = null;
    private String subSwathName = null;
    private String polarization = null;

    private GeoCoding bandGeoCoding = null;
    private SARGeocoding.Orbit mOrbit = null;
    private SARGeocoding.Orbit sOrbit = null;

    private final int polyDegree = 2; // degree of polynomial for orbit fitting
    private final double invalidIndex = -9999.0;
    private final int tileSize = 100;

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public BackGeocodingOp() {
    }

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
            if (sourceProduct == null) {
                return;
            }

            checkSourceProductValidity();

            masterProduct = sourceProduct[0];
            slaveProduct = sourceProduct[1];

            mSU = new Sentinel1Utils(masterProduct);
            sSU = new Sentinel1Utils(slaveProduct);
            sSU.computeDopplerRate();
            sSU.computeReferenceTime();

            mOrbit = mSU.getOrbit(polyDegree);
            sOrbit = sSU.getOrbit(polyDegree);

            mSubSwath = mSU.getSubSwath();
            sSubSwath = sSU.getSubSwath();
			
			final String[] mSubSwathNames = mSU.getSubSwathNames();
			final String[] sSubSwathNames = sSU.getSubSwathNames();
			if (mSubSwathNames.length != 1 || sSubSwathNames.length != 1) {
                throw new OperatorException("Split product is expected.");
            }
			
			if (!mSubSwathNames[0].equals(sSubSwathNames[0])) {
				throw new OperatorException("Same sub-swath is expected.");
			}
			
			subSwathName = mSubSwathNames[0];
			subSwathIndex = 1; // subSwathIndex is always 1 because of split product
            swathIndexStr = mSubSwathNames[0].substring(2);

            final String[] mPolarizations = mSU.getPolarizations();
			final String[] sPolarizations = sSU.getPolarizations();
			if (mPolarizations.length != 1 || sPolarizations.length != 1) {
                throw new OperatorException("Split product with one polarization is expected.");
            }
			
			if (!mPolarizations[0].equals(sPolarizations[0])) {
				throw new OperatorException("Same polarization is expected.");
			}
			
			polarization = mPolarizations[0];

            if (externalDEMFile == null) {
                DEMFactory.checkIfDEMInstalled(demName);
            }

            DEMFactory.validateDEM(demName, masterProduct);

            selectedResampling = ResamplingFactory.createResampling(resamplingType);

            createTargetProduct();

            updateTargetProductMetadata();

            final Band masterBandI = getBand(masterProduct, "i_", swathIndexStr, polarization);
            noDataValue = masterBandI.getNoDataValue();
            bandGeoCoding = masterBandI.getGeoCoding();
            if (bandGeoCoding == null) {
                throw new OperatorException("Master product does not have a geocoding");
            }

        } catch (Throwable e) {
            throw new OperatorException(e.getMessage());
        }
    }

    /**
     * Check source product validity.
     */
    private void checkSourceProductValidity() {

        if (sourceProduct.length != 2) {
            throw new OperatorException("Please select two source products");
        }

        MetadataElement mAbsRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct[0]);
        MetadataElement sAbsRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct[1]);

        final String mMission = mAbsRoot.getAttributeString(AbstractMetadata.MISSION);
        final String sMission = sAbsRoot.getAttributeString(AbstractMetadata.MISSION);
        if (!mMission.startsWith("SENTINEL-1") || !sMission.startsWith("SENTINEL-1")) {
            throw new OperatorException("Source product has invalid mission for Sentinel1 product");
        }

        final String mProductType = mAbsRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE);
        final String sProductType = sAbsRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE);
        if (!mProductType.equals("SLC") || !sProductType.equals("SLC")) {
            throw new OperatorException("Source product should be SLC product");
        }

        final String mAcquisitionMode = mAbsRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);
        final String sAcquisitionMode = sAbsRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);
        if (!mAcquisitionMode.equals(sAcquisitionMode)) {
            throw new OperatorException("Source products should have the same acquisition modes");
        }
        acquisitionMode = mAcquisitionMode;
    }

    /**
     * Create target product.
     */
    private void createTargetProduct() {

        targetProduct = new Product(
                masterProduct.getName(),
                masterProduct.getProductType(),
                masterProduct.getSceneRasterWidth(),
                masterProduct.getSceneRasterHeight());

        final String[] masterBandNames = masterProduct.getBandNames();
        final String mstSuffix = "_mst" + StackUtils.getBandTimeStamp(masterProduct);
        for (String bandName : masterBandNames) {
            if (masterProduct.getBand(bandName) instanceof VirtualBand) {
                continue;
            }
            ProductUtils.copyBand(bandName, masterProduct, bandName + mstSuffix, targetProduct, true);
        }

        final Band masterBand = masterProduct.getBand(masterBandNames[0]);
        final int masterBandWidth = masterBand.getSceneRasterWidth();
        final int masterBandHeight = masterBand.getSceneRasterHeight();

        final String[] slaveBandNames = slaveProduct.getBandNames();
        final String slvSuffix = "_slv1" + StackUtils.getBandTimeStamp(slaveProduct);
        for (String bandName:slaveBandNames) {
            final Band srcBand = slaveProduct.getBand(bandName);
            if (srcBand instanceof VirtualBand) {
                continue;
            }
            final Band targetBand = new Band(
                    bandName + slvSuffix,
                    ProductData.TYPE_FLOAT32,
                    masterBandWidth,
                    masterBandHeight);

            targetBand.setUnit(srcBand.getUnit());
            targetBand.setDescription(srcBand.getDescription());
            targetProduct.addBand(targetBand);
        }

        final Band[] trgBands = targetProduct.getBands();
        for(int i=0; i < trgBands.length; ++i) {
            if(trgBands[i].getUnit().equals(Unit.REAL)) {
                final String suffix = trgBands[i].getName().contains("_mst") ? mstSuffix : slvSuffix;
                ReaderUtils.createVirtualIntensityBand(targetProduct, trgBands[i], trgBands[i+1], suffix);
            }
        }

        ProductUtils.copyProductNodes(masterProduct, targetProduct);
        copySlaveMetadata();

        targetProduct.setPreferredTileSize(targetProduct.getSceneRasterWidth(), tileSize);
        //todo change this line, looking at all swath
        //targetProduct.setPreferredTileSize(targetProduct.getSceneRasterWidth(),
        //        mSubSwath[subSwathIndex - 1].linesPerBurst);

        if (outputRangeAzimuthOffset) {
            final Band azOffsetBand = new Band(
                    "azOffset",
                    ProductData.TYPE_FLOAT32,
                    masterBandWidth,
                    masterBandHeight);

            azOffsetBand.setUnit("Index");
            targetProduct.addBand(azOffsetBand);

            final Band rgOffsetBand = new Band(
                    "rgOffset",
                    ProductData.TYPE_FLOAT32,
                    masterBandWidth,
                    masterBandHeight);

            rgOffsetBand.setUnit("Index");
            targetProduct.addBand(rgOffsetBand);
        }

        if (outputDerampPhase) {
            final Band phaseBand = new Band(
                    "phase",
                    ProductData.TYPE_FLOAT32,
                    masterBandWidth,
                    masterBandHeight);

            phaseBand.setUnit("radian");
            targetProduct.addBand(phaseBand);
        }
    }

    private void copySlaveMetadata() {

        final MetadataElement targetSlaveMetadataRoot = AbstractMetadata.getSlaveMetadata(targetProduct.getMetadataRoot());
        final MetadataElement slvAbsMetadata = AbstractMetadata.getAbstractedMetadata(slaveProduct);
        if (slvAbsMetadata != null) {
            final String timeStamp = StackUtils.getBandTimeStamp(slaveProduct);
            final MetadataElement targetSlaveMetadata = new MetadataElement(slaveProduct.getName() + timeStamp);
            targetSlaveMetadataRoot.addElement(targetSlaveMetadata);
            ProductUtils.copyMetadata(slvAbsMetadata, targetSlaveMetadata);
        }
    }

    /**
     * Update target product metadata.
     */
    private void updateTargetProductMetadata() {

        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.coregistered_stack, 1);

        final MetadataElement inputElem = ProductInformation.getInputProducts(targetProduct);
        final MetadataElement slvInputElem = ProductInformation.getInputProducts(slaveProduct);
        final MetadataAttribute[] slvInputProductAttrbList = slvInputElem.getAttributes();
        for (MetadataAttribute attrib : slvInputProductAttrbList) {
            final MetadataAttribute inputAttrb = AbstractMetadata.addAbstractedAttribute(
                    inputElem, "InputProduct", ProductData.TYPE_ASCII, "", "");
            inputAttrb.getData().setElems(attrib.getData().getElemString());
        }
    }


    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTileMap   The target tiles associated with all target bands to be computed.
     * @param targetRectangle The rectangle of target tile.
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws org.esa.beam.framework.gpf.OperatorException
     *          If an error occurs during computation of the target raster.
     */
    @Override
     public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
             throws OperatorException {

        try {
            final int tx0 = targetRectangle.x;
            final int ty0 = targetRectangle.y;
            final int tw = targetRectangle.width;
            final int th = targetRectangle.height;
            final int tyMax = ty0 + th;
            //System.out.println("tx0 = " + tx0 + ", ty0 = " + ty0 + ", tw = " + tw + ", th = " + th);

            if (!isElevationModelAvailable) {
                getElevationModel();
            }

            for (int burstIndex = 0; burstIndex < mSubSwath[subSwathIndex - 1].numOfBursts; burstIndex++) {
                final int firstLineIdx = burstIndex*mSubSwath[subSwathIndex - 1].linesPerBurst;
                final int lastLineIdx = firstLineIdx + mSubSwath[subSwathIndex - 1].linesPerBurst - 1;

                if (tyMax <= firstLineIdx || ty0 > lastLineIdx) {
                    continue;
                }

				final int ntx0 = tx0;
				final int ntw = tw;
                final int nty0 = Math.max(ty0, firstLineIdx);
                final int ntyMax = Math.min(tyMax, lastLineIdx + 1);
                final int nth = ntyMax - nty0;
                System.out.println("burstIndex = " + burstIndex + ": ntx0 = " + ntx0 + ", nty0 = " + nty0 + ", ntw = " + ntw + ", nth = " + nth);

                double[] tileOverlapPercentage = {0.0, 0.0};
                computeTileOverlapPercentage(ntx0, nty0, ntw, nth, tileOverlapPercentage);

                computePartialTile(subSwathIndex, burstIndex, ntx0, nty0, ntw, nth, targetTileMap,
                        tileOverlapPercentage, pm);
            }

        } catch (Throwable e) {
            throw new OperatorException(e.getMessage());
        }
    }

    private int getSubswathIndex(final String targetBandName) {
        for (int i = 0; i < 5; i++) {
            if (targetBandName.contains(String.valueOf(i+1))){
                return (i+1);
            }
        }
        return -1;
    }
    /**
     * Get elevation model.
     *
     * @throws Exception The exceptions.
     */
    private synchronized void getElevationModel() throws Exception {

        if (isElevationModelAvailable) return;
        try {
            if (externalDEMFile != null) { // if external DEM file is specified by user
                dem = new FileElevationModel(externalDEMFile, demResamplingMethod, externalDEMNoDataValue);
                demNoDataValue = externalDEMNoDataValue;
                demName = externalDEMFile.getPath();
            } else {
                dem = DEMFactory.createElevationModel(demName, demResamplingMethod);
                demNoDataValue = dem.getDescriptor().getNoDataValue();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        isElevationModelAvailable = true;
    }

    private void computeTileOverlapPercentage(final int x0, final int y0, final int w, final int h,
                                              double[] overlapPercentages)
            throws Exception {

        final PixelPos pixPos = new PixelPos();
        final GeoPos geoPos = new GeoPos();
        final double[] earthPoint = new double[3];
        double tileOverlapPercentageMax = -Double.MAX_VALUE;
        double tileOverlapPercentageMin = Double.MAX_VALUE;
        for (int y = y0; y < y0 + h; y += 20) {

            int burstIndex = 0;
            for (burstIndex = 0; burstIndex < mSubSwath[subSwathIndex - 1].numOfBursts; burstIndex++) {
                final int firstLineIdx = burstIndex*mSubSwath[subSwathIndex - 1].linesPerBurst;
                final int lastLineIdx = firstLineIdx + mSubSwath[subSwathIndex - 1].linesPerBurst - 1;

                if (y >= firstLineIdx && y <= lastLineIdx) {
                    break;
                }
            }

            for (int x = x0; x < x0 + w; x += 20) {
                pixPos.setLocation(x, y);
                bandGeoCoding.getGeoPos(pixPos, geoPos);
                final double alt = dem.getElevation(geoPos);
                GeoUtils.geo2xyzWGS84(geoPos.getLat(), geoPos.getLon(), alt, earthPoint);

                final double zeroDopplerTimeInDays = SARGeocoding.getZeroDopplerTime(
                        mSU.firstLineUTC, mSU.lineTimeInterval, mSU.wavelength, earthPoint, mOrbit);

                if (zeroDopplerTimeInDays == SARGeocoding.NonValidZeroDopplerTime) {
                    continue;
                }

                final double zeroDopplerTime = zeroDopplerTimeInDays * Constants.secondsInDay;

                final double azimuthIndex = burstIndex * mSubSwath[subSwathIndex - 1].linesPerBurst +
                        (zeroDopplerTime - mSubSwath[subSwathIndex - 1].burstFirstLineTime[burstIndex]) /
                                mSubSwath[subSwathIndex - 1].azimuthTimeInterval;

                double tileOverlapPercentage = (azimuthIndex - y) / (double) tileSize;

                if (tileOverlapPercentage > tileOverlapPercentageMax) {
                    tileOverlapPercentageMax = tileOverlapPercentage;
                }
                if (tileOverlapPercentage < tileOverlapPercentageMin) {
                    tileOverlapPercentageMin = tileOverlapPercentage;
                }
            }
        }

        if (tileOverlapPercentageMin != Double.MAX_VALUE && tileOverlapPercentageMin < 0.0) {
            overlapPercentages[0] = tileOverlapPercentageMin - 0.5;
        } else {
            overlapPercentages[0] = 0.0;
        }

        if (tileOverlapPercentageMax != -Double.MAX_VALUE && tileOverlapPercentageMax > 0.0) {
            overlapPercentages[1] = tileOverlapPercentageMax + 0.5;
        } else {
            overlapPercentages[1] = 0.0;
        }
    }

    private void computePartialTile(final int subSwathIndex, final int burstIndex,
                                    final int x0, final int y0, final int w, final int h,
                                    final Map<Band, Tile> targetTileMap,
                                    final double[] tileOverlapPercentage,
                                    ProgressMonitor pm)
            throws Exception {

        final PixelPos[][] slavePixPos = computeSlavePixPos(
                subSwathIndex, burstIndex, x0, y0, w, h, tileOverlapPercentage, pm);

        if (slavePixPos == null) {
            return;
        }

        if (outputRangeAzimuthOffset) {
            outputRangeAzimuthOffsets(x0, y0, w, h, targetTileMap, slavePixPos);
        }

        final int margin = selectedResampling.getKernelSize();
        final Rectangle sourceRectangle = getBoundingBox(
                slavePixPos, margin, sSubSwath[subSwathIndex - 1].numOfSamples, sSubSwath[subSwathIndex - 1].numOfLines);

        if (sourceRectangle == null) {
            return;
        }

        final double[][] derampDemodPhase = computeDerampDemodPhase(subSwathIndex, burstIndex, sourceRectangle);

        if (derampDemodPhase == null) {
            return;
        }

        final Band slaveBandI = getBand(slaveProduct, "i_", swathIndexStr, polarization);
        final Band slaveBandQ = getBand(slaveProduct, "q_", swathIndexStr, polarization);
        final Tile slaveTileI = getSourceTile(slaveBandI, sourceRectangle);
        final Tile slaveTileQ = getSourceTile(slaveBandQ, sourceRectangle);

        if (slaveTileI == null || slaveTileQ == null) {
            return;
        }

        final double[][] derampDemodI = new double[sourceRectangle.height][sourceRectangle.width];
        final double[][] derampDemodQ = new double[sourceRectangle.height][sourceRectangle.width];

        performDerampDemod(slaveTileI, slaveTileQ, sourceRectangle, derampDemodPhase, derampDemodI, derampDemodQ);

        performInterpolation(x0, y0, w, h, sourceRectangle, slaveTileI, slaveTileQ, targetTileMap, derampDemodPhase,
                derampDemodI, derampDemodQ, slavePixPos, subSwathIndex, burstIndex);
    }

    private PixelPos[][] computeSlavePixPos(final int subSwathIndex, final int burstIndex,
                                            final int x0, final int y0, final int w, final int h,
                                            final double[] tileOverlapPercentage, ProgressMonitor pm)
            throws Exception {

        try {
            final int xmin = x0;
            final int ymin = Math.max(y0 - (int) (tileSize * tileOverlapPercentage[1]), 0);
            final int ymax = y0 + h + (int) (tileSize * Math.abs(tileOverlapPercentage[0]));
            final int xmax = x0 + w;

            // Compute lat/lon boundaries (with extensions) for target tile
            final double[] latLonMinMax = new double[4];

            computeImageGeoBoundary(xmin, xmax, ymin, ymax, latLonMinMax);

            final double delta = (double)dem.getDescriptor().getDegreeRes() / (double)dem.getDescriptor().getPixelRes();
            final double extralat = 1.5*delta + 4.0/25.0;
            final double extralon = 1.5*delta + 4.0/25.0;
            final double latMin = latLonMinMax[0] - extralat;
            final double latMax = latLonMinMax[1] + extralat;
            final double lonMin = latLonMinMax[2] - extralon;
            final double lonMax = latLonMinMax[3] + extralon;

            // Compute lat/lon indices in DEM for the boundaries;
            final PixelPos upperLeft = dem.getIndex(new GeoPos(latMax, lonMin));
            final PixelPos lowerRight = dem.getIndex(new GeoPos(latMin, lonMax));
            final int latMaxIdx = (int)Math.floor(upperLeft.getY());
            final int latMinIdx = (int)Math.ceil(lowerRight.getY());
            final int lonMinIdx = (int)Math.floor(upperLeft.getX());
            final int lonMaxIdx = (int)Math.ceil(lowerRight.getX());

            // Loop through all DEM points bounded by the indices computed above. For each point,
            // get its lat/lon and its azimuth/range indices in target image;
            final int numLines = latMinIdx - latMaxIdx;
            final int numPixels = lonMaxIdx - lonMinIdx;
            double[][] masterAz = new double[numLines][numPixels];
            double[][] masterRg = new double[numLines][numPixels];
            double[][] slaveAz = new double[numLines][numPixels];
            double[][] slaveRg = new double[numLines][numPixels];
            final PositionData posData = new PositionData();

            for (int l = 0; l < numLines; l++) {
                for (int p = 0; p < numPixels; p++) {

                    GeoPos gp = dem.getGeoPos(new PixelPos(lonMinIdx + p, latMaxIdx + l));
                    final double alt = dem.getElevation(gp);

                    if (alt != demNoDataValue) {
                        GeoUtils.geo2xyzWGS84(gp.lat, gp.lon, alt, posData.earthPoint);
                        if(getPosition(subSwathIndex, burstIndex, mSU, mOrbit, posData)) {

                            masterAz[l][p] = posData.azimuthIndex;
                            masterRg[l][p] = posData.rangeIndex;
                            if (getPosition(subSwathIndex, burstIndex, sSU, sOrbit, posData)) {

                                slaveAz[l][p] = posData.azimuthIndex;
                                slaveRg[l][p] = posData.rangeIndex;
                                continue;
                            }
                        }
                    }

                    masterAz[l][p] = invalidIndex;
                    masterRg[l][p] = invalidIndex;
                }
            }

            // Compute azimuth/range offsets for pixels in target tile using Delaunay interpolation
            final org.jlinda.core.Window tileWindow = new org.jlinda.core.Window(y0, y0 + h - 1, x0, x0 + w - 1);

            //final double rgAzRatio = computeRangeAzimuthSpacingRatio(w, h, latLonMinMax);
            final double rgAzRatio = mSU.rangeSpacing / mSU.azimuthSpacing;

            final double[][] azArray = TriangleUtils.gridDataLinear(
                    masterAz, masterRg, slaveAz, tileWindow, rgAzRatio, 1, 1, invalidIndex, 0);

            if (azArray == null) {
                return null;
            }

            final double[][] rgArray = TriangleUtils.gridDataLinear(
                    masterAz, masterRg, slaveRg, tileWindow, rgAzRatio, 1, 1, invalidIndex, 0);

            if (rgArray == null) {
                return null;
            }

            final PixelPos[][] slavePixelPos = new PixelPos[h][w];
            for(int yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w; xx++) {
                    if (rgArray[yy][xx] == invalidIndex || azArray[yy][xx] == invalidIndex) {
                        slavePixelPos[yy][xx] = null;
                    } else {
                        slavePixelPos[yy][xx] = new PixelPos(rgArray[yy][xx], azArray[yy][xx]);
                    }
                }
            }

            return slavePixelPos;

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("computeSlavePixPos", e);
        }

        return null;
    }

    /**
     * Compute source image geodetic boundary (minimum/maximum latitude/longitude) from the its corner
     * latitude/longitude.
     *
     * @throws Exception The exceptions.
     */
    private void computeImageGeoBoundary(final int xMin, final int xMax, final int yMin, final int yMax,
                                         double[] latLonMinMax) throws Exception {

        final GeoPos geoPosFirstNear = bandGeoCoding.getGeoPos(new PixelPos(xMin, yMin), null);
        final GeoPos geoPosFirstFar = bandGeoCoding.getGeoPos(new PixelPos(xMax, yMin), null);
        final GeoPos geoPosLastNear = bandGeoCoding.getGeoPos(new PixelPos(xMin, yMax), null);
        final GeoPos geoPosLastFar = bandGeoCoding.getGeoPos(new PixelPos(xMax, yMax), null);

        final double[] lats =
                {geoPosFirstNear.getLat(), geoPosFirstFar.getLat(), geoPosLastNear.getLat(), geoPosLastFar.getLat()};

        final double[] lons =
                {geoPosFirstNear.getLon(), geoPosFirstFar.getLon(), geoPosLastNear.getLon(), geoPosLastFar.getLon()};

        double latMin = 90.0;
        double latMax = -90.0;
        for (double lat : lats) {
            if (lat < latMin) {
                latMin = lat;
            }
            if (lat > latMax) {
                latMax = lat;
            }
        }

        double lonMin = 180.0;
        double lonMax = -180.0;
        for (double lon : lons) {
            if (lon < lonMin) {
                lonMin = lon;
            }
            if (lon > lonMax) {
                lonMax = lon;
            }
        }

        latLonMinMax[0] = latMin;
        latLonMinMax[1] = latMax;
        latLonMinMax[2] = lonMin;
        latLonMinMax[3] = lonMax;
    }

    private boolean getPosition(final int subSwathIndex, final int burstIndex, final Sentinel1Utils su,
                                final SARGeocoding.Orbit orbit, final PositionData data) {

        try {
            Sentinel1Utils.SubSwathInfo subSwath = su.getSubSwath()[subSwathIndex - 1];

            final double zeroDopplerTimeInDays = SARGeocoding.getZeroDopplerTime(
                    su.firstLineUTC, su.lineTimeInterval, su.wavelength, data.earthPoint, orbit);

            if (zeroDopplerTimeInDays == SARGeocoding.NonValidZeroDopplerTime) {
                return false;
            }

            final double zeroDopplerTime = zeroDopplerTimeInDays * Constants.secondsInDay;

            data.azimuthIndex = burstIndex * subSwath.linesPerBurst +
                    (zeroDopplerTime - subSwath.burstFirstLineTime[burstIndex]) / subSwath.azimuthTimeInterval;

            final double slantRange = SARGeocoding.computeSlantRange(
                    zeroDopplerTimeInDays, orbit, data.earthPoint, data.sensorPos);

            if (!su.srgrFlag) {
                data.rangeIndex = (slantRange - subSwath.slrTimeToFirstPixel*Constants.lightSpeed) / su.rangeSpacing;
            } else {
                data.rangeIndex = SARGeocoding.computeRangeIndex(
                        su.srgrFlag, su.sourceImageWidth, su.firstLineUTC, su.lastLineUTC,
                        su.rangeSpacing, zeroDopplerTimeInDays, slantRange, su.nearEdgeSlantRange, su.srgrConvParams);
            }

            if (!su.nearRangeOnLeft) {
                data.rangeIndex = su.sourceImageWidth - 1 - data.rangeIndex;
            }
            return true;
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("getPosition", e);
        }
        return false;
    }

    private static Rectangle getBoundingBox(
            final PixelPos[][] slavePixPos, final int margin, final int maxWidth, final int maxHeight) {

        int minX = Integer.MAX_VALUE;
        int maxX = -Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = -Integer.MAX_VALUE;

        for (PixelPos[] slavePixPo : slavePixPos) {
            for (int j = 0; j < slavePixPos[0].length; j++) {
                if (slavePixPo[j] != null) {
                    final int x = (int) Math.floor(slavePixPo[j].getX());
                    final int y = (int) Math.floor(slavePixPo[j].getY());

                    if (x < minX) {
                        minX = x;
                    }
                    if (x > maxX) {
                        maxX = x;
                    }
                    if (y < minY) {
                        minY = y;
                    }
                    if (y > maxY) {
                        maxY = y;
                    }
                }
            }
        }

        if (minX > maxX || minY > maxY) {
            return null;
        }

        minX = Math.max(minX - margin, 0);
        maxX = Math.min(maxX + margin, maxWidth - 1);
        minY = Math.max(minY - margin, 0);
        maxY = Math.min(maxY + margin, maxHeight - 1);

        return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /**
     * Compute combined deramp and demodulation phase for area in slave image defined by rectangle.
     * @param subSwathIndex Sub-swath index
     * @param burstIndex Burst index
     * @param rectangle Rectangle that defines the area in slave image
     * @return The combined deramp and demodulation phase
     */
    private double[][] computeDerampDemodPhase(
            final int subSwathIndex, final int burstIndex, final Rectangle rectangle) {

        try {
            final int x0 = rectangle.x;
            final int y0 = rectangle.y;
            final int w = rectangle.width;
            final int h = rectangle.height;
            final int xMax = x0 + w;
            final int yMax = y0 + h;
            final int s = subSwathIndex - 1;

            final double[][] phase = new double[h][w];
            final int firstLineInBurst = burstIndex*sSubSwath[s].linesPerBurst;
            for (int y = y0; y < yMax; y++) {
                final int yy = y - y0;
                final double ta = (y - firstLineInBurst)*sSubSwath[s].azimuthTimeInterval;
                for (int x = x0; x < xMax; x++) {
                    final int xx = x - x0;
                    final double kt = sSubSwath[s].dopplerRate[burstIndex][x];
                    final double deramp = -Math.PI * kt * Math.pow(ta - sSubSwath[s].referenceTime[burstIndex][x], 2);
                    final double demod = -2 * Math.PI * sSubSwath[s].dopplerCentroid[burstIndex][x] * ta;
                    phase[yy][xx] = deramp + demod;
                }
            }

            return phase;
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("computeDerampDemodPhase", e);
        }

        return null;
    }

    private void performDerampDemod(final Tile slaveTileI, final Tile slaveTileQ,
                                    final Rectangle slaveRectangle, final double[][] derampDemodPhase,
                                    final double[][] derampDemodI, final double[][] derampDemodQ) {

        try {
            final int x0 = slaveRectangle.x;
            final int y0 = slaveRectangle.y;
            final int xMax = x0 + slaveRectangle.width;
            final int yMax = y0 + slaveRectangle.height;

            final ProductData slaveDataI = slaveTileI.getDataBuffer();
            final ProductData slaveDataQ = slaveTileQ.getDataBuffer();
            final TileIndex slvIndex = new TileIndex(slaveTileI);

            for (int y = y0; y < yMax; y++) {
                slvIndex.calculateStride(y);
                final int yy = y - y0;
                for (int x = x0; x < xMax; x++) {
                    final int idx = slvIndex.getIndex(x);
                    final int xx = x - x0;
                    final double valueI = slaveDataI.getElemDoubleAt(idx);
                    final double valueQ = slaveDataQ.getElemDoubleAt(idx);
                    final double cosPhase = Math.cos(derampDemodPhase[yy][xx]);
                    final double sinPhase = Math.sin(derampDemodPhase[yy][xx]);
                    derampDemodI[yy][xx] = valueI*cosPhase - valueQ*sinPhase;
                    derampDemodQ[yy][xx] = valueI*sinPhase + valueQ*cosPhase;
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("performDerampDemod", e);
        }
    }

    private void performInterpolation(final int x0, final int y0, final int w, final int h,
                                      final Rectangle sourceRectangle, final Tile slaveTileI, final Tile slaveTileQ,
                                      final Map<Band, Tile> targetTileMap, final double[][] derampDemodPhase,
                                      final double[][] derampDemodI, final double[][] derampDemodQ,
                                      final PixelPos[][] slavePixPos, final int subswathIndex, final int burstIndex) {

        try {
            final ResamplingRaster resamplingRasterI = new ResamplingRaster(slaveTileI, sourceRectangle, derampDemodI);
            final ResamplingRaster resamplingRasterQ = new ResamplingRaster(slaveTileQ, sourceRectangle, derampDemodQ);
            final ResamplingRaster resamplingRasterPhase = new ResamplingRaster(slaveTileI, sourceRectangle, derampDemodPhase);

            final Band[] targetBands = targetProduct.getBands();
            Band iBand = null;
            Band qBand = null;
            Band phaseBand = null;
            for (Band band : targetBands) {
                final String bandName = band.getName();
                if (bandName.contains("i_") && bandName.contains("_slv")) {
                    iBand = band;
                } else if (bandName.contains("q_") && bandName.contains("_slv")) {
                    qBand = band;
                } else if (bandName.contains("phase")) {
                    phaseBand = band;
                }
            }

            if (iBand == null || qBand == null) {
                return;
            }

            final Tile tgtTileI = targetTileMap.get(iBand);
            final Tile tgtTileQ = targetTileMap.get(qBand);
            final ProductData tgtBufferI = tgtTileI.getDataBuffer();
            final ProductData tgtBufferQ = tgtTileQ.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(tgtTileI);

            Tile tgtTilePhase = null;
            ProductData tgtBufferPhase = null;
            if (outputDerampPhase) {
                tgtTilePhase = targetTileMap.get(phaseBand);
                tgtBufferPhase = tgtTilePhase.getDataBuffer();
            }

            for (int y = y0; y < y0 + h; y++) {
                tgtIndex.calculateStride(y);
                final int yy = y - y0;
                for (int x = x0; x < x0 + w; x++) {
                    final int tgtIdx = tgtIndex.getIndex(x);
                    final PixelPos slavePixelPos = slavePixPos[yy][x - x0];

                    if (slavePixelPos == null) {
                        tgtBufferI.setElemDoubleAt(tgtIdx, noDataValue);
                        tgtBufferQ.setElemDoubleAt(tgtIdx, noDataValue);

                        if (outputDerampPhase) {
                            tgtBufferPhase.setElemFloatAt(tgtIdx, (float)noDataValue);
                        }
                        continue;
                    }

                    if (isSlavePixPosValid(slavePixelPos, subswathIndex, burstIndex)) {
                        final Resampling.Index resamplingIndex = selectedResampling.createIndex();

                        selectedResampling.computeIndex(slavePixelPos.x, slavePixelPos.y,
                                sSubSwath[subswathIndex - 1].numOfSamples, sSubSwath[subswathIndex - 1].numOfLines,
                                resamplingIndex);

                        final double samplePhase = selectedResampling.resample(resamplingRasterPhase, resamplingIndex);
                        final double sampleI = selectedResampling.resample(resamplingRasterI, resamplingIndex);
                        final double sampleQ = selectedResampling.resample(resamplingRasterQ, resamplingIndex);
                        final double cosPhase = Math.cos(samplePhase);
                        final double sinPhase = Math.sin(samplePhase);
                        double rerampRemodI = sampleI * cosPhase + sampleQ * sinPhase;
                        double rerampRemodQ = -sampleI * sinPhase + sampleQ * cosPhase;

                        if (Double.isNaN(rerampRemodI)) {
                            rerampRemodI = noDataValue;
                        }

                        if (Double.isNaN(rerampRemodQ)) {
                            rerampRemodQ = noDataValue;
                        }

                        tgtBufferI.setElemDoubleAt(tgtIdx, rerampRemodI);
                        tgtBufferQ.setElemDoubleAt(tgtIdx, rerampRemodQ);

                        if (outputDerampPhase) {
                            tgtBufferPhase.setElemFloatAt(tgtIdx, (float)samplePhase);
                        }

                    } else {
                        tgtBufferI.setElemDoubleAt(tgtIdx, noDataValue);
                        tgtBufferQ.setElemDoubleAt(tgtIdx, noDataValue);
                        if (outputDerampPhase) {
                            tgtBufferPhase.setElemFloatAt(tgtIdx, (float)noDataValue);
                        }
                    }
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("performInterpolation", e);
        }
    }

    private String getPolarization(final String bandName) {
        if (bandName.contains("HH")) {
            return "HH";
        } else if (bandName.contains("HV")) {
            return "HV";
        } else if (bandName.contains("VV")) {
            return "VV";
        } else if (bandName.contains("VH")) {
            return "VH";
        } else {
            throw new OperatorException("Unknown polarization in target band " + bandName);
        }
    }

    private Band getBand(
            final Product product, final String prefix, final String swathIndexStr, final String polarization) {

        final String[] bandNames = product.getBandNames();
        for (String bandName:bandNames) {
            if (bandName.contains(prefix) && bandName.contains(swathIndexStr) && bandName.contains(polarization)) {
                return product.getBand(bandName);
            }
        }
        return null;
    }

    private boolean isSlavePixPosValid(final PixelPos slavePixPos, final int subswathIndex, final int burstIndex) {
        return (slavePixPos != null &&
                slavePixPos.x >= sSubSwath[subswathIndex - 1].firstValidPixel &&
                slavePixPos.x <= sSubSwath[subswathIndex - 1].lastValidPixel &&
                slavePixPos.y >= sSubSwath[subswathIndex - 1].linesPerBurst*burstIndex &&
                slavePixPos.y < sSubSwath[subswathIndex - 1].linesPerBurst*(burstIndex+1));
    }

    private void outputRangeAzimuthOffsets(final int x0, final int y0, final int w, final int h,
                                           final Map<Band, Tile> targetTileMap, final PixelPos[][] slavePixPos) {

        try {
            final Band azOffsetBand = targetProduct.getBand("azOffset");
            final Band rgOffsetBand = targetProduct.getBand("rgOffset");

            if (azOffsetBand == null || rgOffsetBand == null) {
                return;
            }

            final Tile tgtTileAzOffset = targetTileMap.get(azOffsetBand);
            final Tile tgtTileRgOffset = targetTileMap.get(rgOffsetBand);
            final ProductData tgtBufferAzOffset = tgtTileAzOffset.getDataBuffer();
            final ProductData tgtBufferRgOffset = tgtTileRgOffset.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(tgtTileAzOffset);

            for (int y = y0; y < y0 + h; y++) {
                tgtIndex.calculateStride(y);
                final int yy = y - y0;
                for (int x = x0; x < x0 + w; x++) {
                    final int tgtIdx = tgtIndex.getIndex(x);
                    final int xx = x - x0;

                    if (slavePixPos[yy][xx] == null) {
                        tgtBufferAzOffset.setElemFloatAt(tgtIdx, (float) noDataValue);
                        tgtBufferRgOffset.setElemFloatAt(tgtIdx, (float) noDataValue);
                    } else {
                        tgtBufferAzOffset.setElemFloatAt(tgtIdx, (float)(y - slavePixPos[yy][xx].y));
                        tgtBufferRgOffset.setElemFloatAt(tgtIdx, (float)(x - slavePixPos[yy][xx].x));
                    }
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("outputRangeAzimuthOffsets", e);
        }
    }

    private static class PositionData {
        final double[] earthPoint = new double[3];
        final double[] sensorPos = new double[3];
        double azimuthIndex;
        double rangeIndex;
        double azimuthTime;
        double slantRangeTime;
    }

    private static class Index {
        public int i0;
        public int i1;
        public int j0;
        public int j1;
        public double muX;
        public double muY;

        public Index() {
        }
    }

    private static class ResamplingRaster implements Resampling.Raster {

        private final int x0;
        private final int y0;
        private final Tile tile;
        private final double[][] data;
        private final boolean usesNoData;
        private final boolean scalingApplied;
        private final double noDataValue;
        private final double geophysicalNoDataValue;

        public ResamplingRaster(final Tile tile, final Rectangle rectangle, final double[][] data) {
            this.x0 = rectangle.x;
            this.y0 = rectangle.y;
            this.tile = tile;
            this.data = data;
            final RasterDataNode rasterDataNode = tile.getRasterDataNode();
            this.usesNoData = rasterDataNode.isNoDataValueUsed();
            this.noDataValue = rasterDataNode.getNoDataValue();
            this.geophysicalNoDataValue = rasterDataNode.getGeophysicalNoDataValue();
            this.scalingApplied = rasterDataNode.isScalingApplied();
        }

        public final int getWidth() {
            return tile.getWidth();
        }

        public final int getHeight() {
            return tile.getHeight();
        }

        public boolean getSamples(final int[] x, final int[] y, final double[][] samples) throws Exception {
            boolean allValid = true;

            for (int i = 0; i < y.length; i++) {
                final int yy = y[i] - y0;
                for (int j = 0; j < x.length; j++) {

                    samples[i][j] = data[yy][x[j] - x0];

                    if (usesNoData) {
                        if (scalingApplied && geophysicalNoDataValue == samples[i][j] || noDataValue == samples[i][j]) {
                            samples[i][j] = Double.NaN;
                            allValid = false;
                        }
                    }
                }
            }

            return allValid;
        }
    }

    private static class TriangleUtils {

        public static double[][] gridDataLinear(final double[][] x_in, final double[][] y_in, final double[][] z_in,
                                                final org.jlinda.core.Window window, final double xyRatio,
                                                final int xScale, final int yScale, final double invalidIndex,
                                                final int offset)
                throws Exception {

            final FastDelaunayTriangulator FDT = triangulate(x_in, y_in, z_in, xyRatio, invalidIndex);

            if (FDT == null) {
                return null;
            }

            return interpolate(xyRatio, window, xScale, yScale, offset, invalidIndex, FDT);

        }

        private static FastDelaunayTriangulator triangulate(final double[][] x_in, final double[][] y_in,
                                                            final double[][] z_in, final double xyRatio,
                                                            final double invalidIndex)
                throws Exception {

            java.util.List<Geometry> list = new ArrayList<>();
            GeometryFactory gf = new GeometryFactory();
            for (int i = 0; i < x_in.length; i++) {
                for (int j = 0; j < x_in[0].length; j++) {
                    if (x_in[i][j] == invalidIndex || y_in[i][j] == invalidIndex) {
                        continue;
                    }
                    list.add(gf.createPoint(new Coordinate(x_in[i][j], y_in[i][j] * xyRatio, z_in[i][j])));
                }
            }

            if (list.size() < 3) {
                return null;
            }

            FastDelaunayTriangulator FDT = new FastDelaunayTriangulator();
            try {
                FDT.triangulate(list.iterator());
            } catch (TriangulationException te) {
                te.printStackTrace();
            }

            return FDT;
        }

        private static double[][] interpolate(double xyRatio, final org.jlinda.core.Window tileWindow,
                                              final double xScale, final double yScale,
                                              final double offset, final double invalidIndex,
                                              FastDelaunayTriangulator FDT) {

            final int zLoops = 1;
            final double x_min = tileWindow.linelo;
            final double y_min = tileWindow.pixlo;

            int i, j; // counters
            long i_min, i_max, j_min, j_max; // minimas/maximas
            double xp, yp;
            double xkj, ykj, xlj, ylj;
            double f; // function
            double zj, zk, zl, zkj, zlj;

            // containers
            int zLoop; // z-level - hardcoded!
            double[] a = new double[zLoops];
            double[] b = new double[zLoops];
            double[] c = new double[zLoops];
            // containers for xy coordinates of Triangles: p1-p2-p3-p1
            double[] vx = new double[4];
            double[] vy = new double[4];

            // declare demRadarCode_phase
            double[][] griddedData = new double[(int) tileWindow.lines()][(int) tileWindow.pixels()];
            final int nx = griddedData.length / zLoops;
            final int ny = griddedData[0].length;

            for (double[] aGriddedData : griddedData) {
                Arrays.fill(aGriddedData, invalidIndex);
            }

            // interpolate: loop over triangles
            for (Triangle triangle : FDT.triangles) {

                // store triangle coordinates in local variables
                vx[0] = vx[3] = triangle.getA().x;
                vy[0] = vy[3] = triangle.getA().y / xyRatio;

                vx[1] = triangle.getB().x;
                vy[1] = triangle.getB().y / xyRatio;

                vx[2] = triangle.getC().x;
                vy[2] = triangle.getC().y / xyRatio;

                // skip invalid indices
                if (vx[0] == invalidIndex || vx[1] == invalidIndex || vx[2] == invalidIndex ||
                    vy[0] == invalidIndex || vy[1] == invalidIndex || vy[2] == invalidIndex) {
                    continue;
                }

                // Compute grid indices the current triangle may cover
                xp = Math.min(Math.min(vx[0], vx[1]), vx[2]);
                i_min = coordToIndex(xp, x_min, xScale, offset);

                xp = Math.max(Math.max(vx[0], vx[1]), vx[2]);
                i_max = coordToIndex(xp, x_min, xScale, offset);

                yp = Math.min(Math.min(vy[0], vy[1]), vy[2]);
                j_min = coordToIndex(yp, y_min, yScale, offset);

                yp = Math.max(Math.max(vy[0], vy[1]), vy[2]);
                j_max = coordToIndex(yp, y_min, yScale, offset);

                // skip triangle that is above or below the region
                if ((i_max < 0) || (i_min >= nx)) {
                    continue;
                }

                // skip triangle that is on the left or right of the region
                if ((j_max < 0) || (j_min >= ny)) {
                    continue;
                }

                // triangle covers the upper or lower boundary
                if (i_min < 0) {
                    i_min = 0;
                }

                if (i_max >= nx) {
                    i_max = nx - 1;
                }

                // triangle covers left or right boundary
                if (j_min < 0) {
                    j_min = 0;
                }

                if (j_max >= ny) {
                    j_max = ny - 1;
                }

                // compute plane defined by the three vertices of the triangle: z = ax + by + c
                xkj = vx[1] - vx[0];
                ykj = vy[1] - vy[0];
                xlj = vx[2] - vx[0];
                ylj = vy[2] - vy[0];

                f = 1.0 / (xkj * ylj - ykj * xlj);

                for (zLoop = 0; zLoop < zLoops; zLoop++) {
                    zj = triangle.getA().z;
                    zk = triangle.getB().z;
                    zl = triangle.getC().z;
                    zkj = zk - zj;
                    zlj = zl - zj;
                    a[zLoop] = -f * (ykj * zlj - zkj * ylj);
                    b[zLoop] = -f * (zkj * xlj - xkj * zlj);
                    c[zLoop] = -a[zLoop] * vx[1] - b[zLoop] * vy[1] + zk;
                }

                for (i = (int) i_min; i <= i_max; i++) {
                    xp = indexToCoord(i, x_min, xScale, offset);
                    for (j = (int) j_min; j <= j_max; j++) {
                        yp = indexToCoord(j, y_min, yScale, offset);

                        if (!pointInTriangle(vx, vy, xp, yp)) {
                            continue;
                        }

                        for (zLoop = 0; zLoop < zLoops; zLoop++) {
                            griddedData[i][j] = a[zLoop] * xp + b[zLoop] * yp + c[zLoop];
                        }
                    }
                }
            }

            return griddedData;
        }

        private static boolean pointInTriangle(double[] xt, double[] yt, double x, double y) {
            int iRet0 = ((xt[2] - xt[0]) * (y - yt[0])) > ((x - xt[0]) * (yt[2] - yt[0])) ? 1 : -1;
            int iRet1 = ((xt[0] - xt[1]) * (y - yt[1])) > ((x - xt[1]) * (yt[0] - yt[1])) ? 1 : -1;
            int iRet2 = ((xt[1] - xt[2]) * (y - yt[2])) > ((x - xt[2]) * (yt[1] - yt[2])) ? 1 : -1;

            return (iRet0 > 0 && iRet1 > 0 && iRet2 > 0) || (iRet0 < 0 && iRet1 < 0 && iRet2 < 0);
        }

        private static long coordToIndex(final double coord, final double coord0, final double deltaCoord, final double offset) {
            return irint((((coord - coord0) / (deltaCoord)) - offset));
        }

        private static double indexToCoord(final long idx, final double coord0, final double deltaCoord, final double offset) {
            return (coord0 + idx * deltaCoord + offset);
        }

        private static long irint(final double coord) {
            return ((long) rint(coord));
        }

        private static double rint(final double coord) {
            return Math.floor(coord + 0.5);
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
            super(BackGeocodingOp.class);
        }
    }
}