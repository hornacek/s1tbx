package org.jlinda.nest.gpf;

import org.esa.nest.dataio.dem.ElevationModelDescriptor;
import org.esa.nest.dataio.dem.ElevationModelRegistry;
import org.esa.snap.gpf.ui.UIValidation;
import org.esa.beam.framework.ui.AppContext;
import org.esa.beam.visat.VisatApp;
import org.esa.snap.gpf.OperatorUIUtils;
import org.esa.snap.gpf.ui.BaseOperatorUI;
import org.esa.snap.util.DialogUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.File;
import java.util.Map;

/**
 * User interface for SubtRefDemOp
 */
public class SimulateAmplitudeOpUI extends BaseOperatorUI {

    private static final ElevationModelDescriptor[] descriptors = ElevationModelRegistry.getInstance().getAllDescriptors();
    private static final String[] demValueSet = new String[descriptors.length];

    static {
        for (int i = 0; i < descriptors.length; i++) {
            demValueSet[i] = descriptors[i].getName();
        }
    }

    private final JTextField orbitDegree = new JTextField("");
    private final JComboBox demName = new JComboBox(demValueSet);
//    private final JComboBox demName = new JComboBox();

    private static final String externalDEMStr = "External DEM";
    private final JTextField externalDEMFile = new JTextField("");
    private final JTextField externalDEMNoDataValue = new JTextField("");
    private final JButton externalDEMBrowseButton = new JButton("...");
    private final JLabel externalDEMFileLabel = new JLabel("External DEM:");
    private final JLabel externalDEMNoDataValueLabel = new JLabel("DEM No Data Value:");

    private final JTextField simAmpBandName = new JTextField("");

    private Double extNoDataValue = 0.0;
    private final DialogUtils.TextAreaKeyListener textAreaKeyListener = new DialogUtils.TextAreaKeyListener();

    @Override
    public JComponent CreateOpTab(String operatorName, Map<String, Object> parameterMap, AppContext appContext) {

        demName.addItem(externalDEMStr);

        initializeOperatorUI(operatorName, parameterMap);

        final JComponent panel = createPanel();
        initParameters();

        demName.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent event) {
                final String item = ((String)demName.getSelectedItem()).replace(" (Auto Download)", "");
                if(item.equals(externalDEMStr)) {
                    enableExternalDEM(true);
                } else {
                    externalDEMFile.setText("");
                    enableExternalDEM(false);
                }
            }
        });
        externalDEMFile.setColumns(30);
        final String demItem = ((String)demName.getSelectedItem()).replace(" (Auto Download)", "");
        enableExternalDEM(demItem.equals(externalDEMStr));

        externalDEMBrowseButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final File file = VisatApp.getApp().showFileOpenDialog("External DEM File", false, null);
                externalDEMFile.setText(file.getAbsolutePath());
                extNoDataValue = OperatorUIUtils.getNoDataValue(file);
                externalDEMNoDataValue.setText(String.valueOf(extNoDataValue));
            }
        });

        externalDEMNoDataValue.addKeyListener(textAreaKeyListener);

        return new JScrollPane(panel);
    }


    @Override
    public void initParameters() {
        orbitDegree.setText(String.valueOf(paramMap.get("orbitDegree")));
        demName.setSelectedItem(paramMap.get("demName"));
        final File extFile = (File)paramMap.get("externalDEMFile");
        if(extFile != null) {
            externalDEMFile.setText(extFile.getAbsolutePath());
            extNoDataValue =  (Double)paramMap.get("externalDEMNoDataValue");
            if(extNoDataValue != null && !textAreaKeyListener.isChangedByUser()) {
                externalDEMNoDataValue.setText(String.valueOf(extNoDataValue));
            }
        }
        simAmpBandName.setText(String.valueOf(paramMap.get("simAmpBandName")));
    }

    @Override
    public UIValidation validateParameters() {
        return new UIValidation(UIValidation.State.OK, "");
    }

    @Override
    public void updateParameters() {
        paramMap.put("orbitDegree", Integer.parseInt(orbitDegree.getText()));
        paramMap.put("demName", demName.getSelectedItem());
        final String extFileStr = externalDEMFile.getText();
        if(!extFileStr.isEmpty()) {
            paramMap.put("externalDEMFile", new File(extFileStr));
            paramMap.put("externalDEMNoDataValue", Double.parseDouble(externalDEMNoDataValue.getText()));
        }
        paramMap.put("simAmpBandName", simAmpBandName.getText());
    }

    private JComponent createPanel() {

        final JPanel contentPane = new JPanel();
        contentPane.setLayout(new GridBagLayout());
        final GridBagConstraints gbc = DialogUtils.createGridBagConstraints();

        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Orbit Interpolation Degree:", orbitDegree);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Digital Elevation Model:", demName);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, externalDEMFileLabel, externalDEMFile);
        gbc.gridx = 2;
        contentPane.add(externalDEMBrowseButton, gbc);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, externalDEMNoDataValueLabel, externalDEMNoDataValue);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Simulated Amplitude Band Name:", simAmpBandName);
        gbc.gridy++;

        DialogUtils.fillPanel(contentPane, gbc);

        return contentPane;
    }


    private void enableExternalDEM(boolean flag) {
            DialogUtils.enableComponents(externalDEMFileLabel, externalDEMFile, flag);
            DialogUtils.enableComponents(externalDEMNoDataValueLabel, externalDEMNoDataValue, flag);
            externalDEMBrowseButton.setVisible(flag);
        }

}