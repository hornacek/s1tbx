package org.esa.nest;

import com.bc.ceres.core.ProgressMonitor;
import junit.framework.TestCase;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.util.StopWatch;
import org.esa.nest.datamodel.AbstractMetadata;
import org.esa.nest.util.MemUtils;
import org.esa.nest.util.TestUtils;

/**
 * Benchmark code
 */
public abstract class Benchmark extends TestCase {
    private OperatorSpi spi;

    protected boolean skipS1 = false;

    @Override
    protected void setUp() throws Exception {
        TestUtils.initTestEnvironment();
        DataSets.instance();

        spi = CreateOperatorSpi();
        GPF.getDefaultInstance().getOperatorSpiRegistry().addOperatorSpi(spi);
    }

    @Override
    protected void tearDown() throws Exception {
        GPF.getDefaultInstance().getOperatorSpiRegistry().removeOperatorSpi(spi);
    }

    protected abstract OperatorSpi CreateOperatorSpi();

    //RS-2
    public void testPerf_RS2_Quad() throws Exception {
        process(spi, DataSets.instance().RS2_quad_product);
    }

    //ASAR
    public void testPerf_ASAR_IMS() throws Exception {
        process(spi, DataSets.instance().ASAR_IMS_product);
    }

    public void testPerf_ASAR_IMP() throws Exception {
        process(spi, DataSets.instance().ASAR_IMP_product);
    }

    public void testPerf_ASAR_APP() throws Exception {
        process(spi, DataSets.instance().ASAR_APP_product);
    }

    public void testPerf_ASAR_APS() throws Exception {
        process(spi, DataSets.instance().ASAR_APS_product);
    }

    public void testPerf_ASAR_WMS() throws Exception {
        process(spi, DataSets.instance().ASAR_WMS_product);
    }

    //ERS-1
    public void testPerf_ERS1_PRI() throws Exception {
        process(spi, DataSets.instance().ERS1_PRI_product);
    }

    public void testPerf_ERS1_SLC() throws Exception {
        process(spi, DataSets.instance().ERS1_SLC_product);
    }

    //ERS-2
    public void testPerf_ERS2_IMP() throws Exception {
        process(spi, DataSets.instance().ERS2_IMP_product);
    }

    public void testPerf_ERS2_IMS() throws Exception {
        process(spi, DataSets.instance().ERS2_IMS_product);
    }

    //ALOS
    public void testPerf_ALOS_L11() throws Exception {
        process(spi, DataSets.instance().ALOS_L11_product);
    }

    //TerraSAR-X
    public void testPerf_TSX_SSC() throws Exception {
        process(spi, DataSets.instance().TSX_SSC_product);
    }

    public void testPerf_TSX_SSC_Quad() throws Exception {
        process(spi, DataSets.instance().TSX_SSC_Quad_product);
    }

    //S-1
    public void testPerf_S1_GRD() throws Exception {
        if(skipS1) return;
        process(spi, DataSets.instance().S1_GRD_product);
    }

    private void process(final OperatorSpi spi, final Product product) throws Exception {
        if(!BenchConstants.runBenchmarks) return;

        //warm up
        run(spi, product);

        final StopWatch watch = new StopWatch();
        for(int i=0; i< BenchConstants.numIterations; ++i) {
            MemUtils.freeAllMemory();
            run(spi, product);
        }
        watch.stop();

        final String mission = AbstractMetadata.getAbstractedMetadata(product).getAttributeString(AbstractMetadata.MISSION);
        final int w = product.getSceneRasterWidth();
        final int h = product.getSceneRasterHeight();

        System.out.println(spi.getOperatorAlias() +' '+ mission +' '+ product.getProductType() +' '+w +'x'+ h
                +" avg time: "+ StopWatch.getTimeString(watch.getTimeDiff() / BenchConstants.numIterations));
    }

    private void run(final OperatorSpi spi, final Product product) throws Exception {
        final Operator op = spi.createOperator();
        op.setSourceProduct(product);
        setOperatorParameters(op);
        executeOperator(op, BenchConstants.maxDimensions);
        op.dispose();
    }

    public static void executeOperator(final Operator op, final int dimensions) throws Exception {
        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, false, false);

        // readPixels: execute computeTiles()
        final int w = Math.min(targetProduct.getSceneRasterWidth(), dimensions);
        final int h = Math.min(targetProduct.getSceneRasterHeight(), dimensions);
        final float[] floatValues = new float[w*h];
        final Band targetBand = targetProduct.getBandAt(0);
        targetBand.readPixels(0, 0, w, h, floatValues, ProgressMonitor.NULL);

        targetProduct.dispose();
    }

    protected void setOperatorParameters(final Operator op) {

    }
}