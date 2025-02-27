// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.gui.io;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.swing.AbstractAction;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.actions.SessionSaveAction;
import org.openstreetmap.josm.actions.UploadAction;
import org.openstreetmap.josm.gui.ExceptionDialogUtil;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.io.SaveLayersModel.Mode;
import org.openstreetmap.josm.gui.layer.AbstractModifiableLayer;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.gui.progress.swing.SwingRenderingProgressMonitor;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.util.WindowGeometry;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.ImageProvider.ImageSizes;
import org.openstreetmap.josm.tools.ImageResource;
import org.openstreetmap.josm.tools.InputMapUtils;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.UserCancelException;
import org.openstreetmap.josm.tools.Utils;

/**
 * Dialog that pops up when the user closes a layer with modified data.
 * <p>
 * It asks for confirmation that all modifications should be discarded and offer
 * to save the layers to file or upload to server, depending on the type of layer.
 */
public class SaveLayersDialog extends JDialog implements TableModelListener {

    /**
     * The cause for requesting an action on unsaved modifications
     */
    public enum Reason {
        /** deleting a layer */
        DELETE,
        /** exiting JOSM */
        EXIT,
        /** restarting JOSM */
        RESTART
    }

    /**
     * The action a user decided to take with respect to an operation
     */
    enum UserAction {
        /** save/upload layers was successful, proceed with operation */
        PROCEED,
        /** save/upload of layers was not successful or user canceled operation */
        CANCEL
    }

    private final SaveLayersModel model = new SaveLayersModel();
    private UserAction action = UserAction.CANCEL;
    private final UploadAndSaveProgressRenderer pnlUploadLayers = new UploadAndSaveProgressRenderer();

    private final SaveAndProceedAction saveAndProceedAction = new SaveAndProceedAction();
    private final SaveSessionButtonAction saveSessionAction = new SaveSessionButtonAction();
    private final DiscardAndProceedAction discardAndProceedAction = new DiscardAndProceedAction();
    private final CancelAction cancelAction = new CancelAction();
    private transient SaveAndUploadTask saveAndUploadTask;

    private final JButton saveAndProceedActionButton = new JButton(saveAndProceedAction);

    /**
     * Asks user to perform "save layer" operations (save on disk and/or upload data to server) before data layers deletion.
     *
     * @param selectedLayers The layers to check. Only instances of {@link AbstractModifiableLayer} are considered.
     * @param reason the cause for requesting an action on unsaved modifications
     * @return {@code true} if there was nothing to save, or if the user wants to proceed to save operations.
     *         {@code false} if the user cancels.
     * @since 11093
     */
    public static boolean saveUnsavedModifications(Iterable<? extends Layer> selectedLayers, Reason reason) {
        if (!GraphicsEnvironment.isHeadless()) {
            SaveLayersDialog dialog = new SaveLayersDialog(MainApplication.getMainFrame());
            List<AbstractModifiableLayer> layersWithUnsavedChanges = new ArrayList<>();
            for (Layer l: selectedLayers) {
                if (!(l instanceof AbstractModifiableLayer)) {
                    continue;
                }
                AbstractModifiableLayer odl = (AbstractModifiableLayer) l;
                if (odl.isModified() && (
                        (odl.isSavable() && odl.requiresSaveToFile()) ||
                        (odl.isUploadable() && odl.requiresUploadToServer() && !odl.isUploadDiscouraged()))) {
                    layersWithUnsavedChanges.add(odl);
                }
            }
            dialog.prepareForSavingAndUpdatingLayers(reason);
            if (!layersWithUnsavedChanges.isEmpty()) {
                dialog.getModel().populate(layersWithUnsavedChanges);
                dialog.setVisible(true);
                switch (dialog.getUserAction()) {
                    case PROCEED: return true;
                    case CANCEL:
                    default: return false;
                }
            }
            dialog.closeDialog();
        }

        return true;
    }

    /**
     * Constructs a new {@code SaveLayersDialog}.
     * @param parent parent component
     */
    public SaveLayersDialog(Component parent) {
        super(GuiHelper.getFrameForComponent(parent), ModalityType.DOCUMENT_MODAL);
        build();
    }

    /**
     * builds the GUI
     */
    protected void build() {
        WindowGeometry geometry = WindowGeometry.centerOnScreen(new Dimension(650, 300));
        geometry.applySafe(this);
        getContentPane().setLayout(new BorderLayout());

        SaveLayersTable table = new SaveLayersTable(model);
        JScrollPane pane = new JScrollPane(table);
        model.addPropertyChangeListener(table);
        table.getModel().addTableModelListener(this);

        getContentPane().add(pane, BorderLayout.CENTER);
        getContentPane().add(buildButtonRow(), BorderLayout.SOUTH);

        addWindowListener(new WindowClosingAdapter());
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    }

    /**
     * builds the button row
     *
     * @return the panel with the button row
     */
    protected JPanel buildButtonRow() {
        JPanel pnl = new JPanel(new GridBagLayout());

        model.addPropertyChangeListener(saveAndProceedAction);
        pnl.add(saveAndProceedActionButton, GBC.std(0, 0).insets(5, 5, 0, 0).fill(GridBagConstraints.HORIZONTAL));

        pnl.add(new JButton(saveSessionAction), GBC.std(1, 0).insets(5, 5, 5, 0).fill(GridBagConstraints.HORIZONTAL));

        model.addPropertyChangeListener(discardAndProceedAction);
        pnl.add(new JButton(discardAndProceedAction), GBC.std(0, 1).insets(5, 5, 0, 5).fill(GridBagConstraints.HORIZONTAL));

        pnl.add(new JButton(cancelAction), GBC.std(1, 1).insets(5, 5, 5, 5).fill(GridBagConstraints.HORIZONTAL));

        JPanel pnl2 = new JPanel(new BorderLayout());
        pnl2.add(pnlUploadLayers, BorderLayout.CENTER);
        model.addPropertyChangeListener(pnlUploadLayers);
        pnl2.add(pnl, BorderLayout.SOUTH);
        return pnl2;
    }

    public void prepareForSavingAndUpdatingLayers(final Reason reason) {
        switch (reason) {
            case EXIT:
                setTitle(tr("Unsaved changes - Save/Upload before exiting?"));
                break;
            case DELETE:
                setTitle(tr("Unsaved changes - Save/Upload before deleting?"));
                break;
            case RESTART:
                setTitle(tr("Unsaved changes - Save/Upload before restarting?"));
                break;
        }
        this.saveAndProceedAction.initForReason(reason);
        this.discardAndProceedAction.initForReason(reason);
    }

    public UserAction getUserAction() {
        return this.action;
    }

    public SaveLayersModel getModel() {
        return model;
    }

    protected void launchSafeAndUploadTask() {
        ProgressMonitor monitor = new SwingRenderingProgressMonitor(pnlUploadLayers);
        monitor.beginTask(tr("Uploading and saving modified layers ..."));
        this.saveAndUploadTask = new SaveAndUploadTask(model, monitor);
        new Thread(saveAndUploadTask, saveAndUploadTask.getClass().getName()).start();
    }

    protected void cancelSafeAndUploadTask() {
        if (this.saveAndUploadTask != null) {
            this.saveAndUploadTask.cancel();
        }
        model.setMode(Mode.EDITING_DATA);
    }

    private static class LayerListWarningMessagePanel extends JPanel {
        static final class LayerCellRenderer implements ListCellRenderer<SaveLayerInfo> {
            private final DefaultListCellRenderer def = new DefaultListCellRenderer();

            @Override
            public Component getListCellRendererComponent(JList<? extends SaveLayerInfo> list, SaveLayerInfo info, int index,
                    boolean isSelected, boolean cellHasFocus) {
                def.setIcon(info.getLayer().getIcon());
                def.setText(info.getName());
                return def;
            }
        }

        private final JLabel lblMessage = new JLabel();
        private final JList<SaveLayerInfo> lstLayers = new JList<>();

        LayerListWarningMessagePanel(String msg, List<SaveLayerInfo> infos) {
            super(new GridBagLayout());
            build();
            lblMessage.setText(msg);
            lstLayers.setListData(infos.toArray(new SaveLayerInfo[0]));
        }

        protected void build() {
            GridBagConstraints gc = new GridBagConstraints();
            gc.gridx = 0;
            gc.gridy = 0;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.weightx = 1.0;
            gc.weighty = 0.0;
            add(lblMessage, gc);
            lblMessage.setHorizontalAlignment(SwingConstants.LEADING);
            lstLayers.setCellRenderer(new LayerCellRenderer());
            gc.gridx = 0;
            gc.gridy = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.weightx = 1.0;
            gc.weighty = 1.0;
            add(lstLayers, gc);
        }
    }

    private static void warn(String msg, List<SaveLayerInfo> infos, String title) {
        JPanel panel = new LayerListWarningMessagePanel(msg, infos);
        JOptionPane.showConfirmDialog(MainApplication.getMainFrame(), panel, title, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE);
    }

    protected static void warnLayersWithConflictsAndUploadRequest(List<SaveLayerInfo> infos) {
        warn(trn("<html>{0} layer has unresolved conflicts.<br>"
                + "Either resolve them first or discard the modifications.<br>"
                + "Layer with conflicts:</html>",
                "<html>{0} layers have unresolved conflicts.<br>"
                + "Either resolve them first or discard the modifications.<br>"
                + "Layers with conflicts:</html>",
                infos.size(),
                infos.size()),
             infos, tr("Unsaved data and conflicts"));
    }

    protected static void warnLayersWithoutFilesAndSaveRequest(List<SaveLayerInfo> infos) {
        warn(trn("<html>{0} layer needs saving but has no associated file.<br>"
                + "Either select a file for this layer or discard the changes.<br>"
                + "Layer without a file:</html>",
                "<html>{0} layers need saving but have no associated file.<br>"
                + "Either select a file for each of them or discard the changes.<br>"
                + "Layers without a file:</html>",
                infos.size(),
                infos.size()),
             infos, tr("Unsaved data and missing associated file"));
    }

    protected static void warnLayersWithIllegalFilesAndSaveRequest(List<SaveLayerInfo> infos) {
        warn(trn("<html>{0} layer needs saving but has an associated file<br>"
                + "which cannot be written.<br>"
                + "Either select another file for this layer or discard the changes.<br>"
                + "Layer with a non-writable file:</html>",
                "<html>{0} layers need saving but have associated files<br>"
                + "which cannot be written.<br>"
                + "Either select another file for each of them or discard the changes.<br>"
                + "Layers with non-writable files:</html>",
                infos.size(),
                infos.size()),
             infos, tr("Unsaved data non-writable files"));
    }

    static boolean confirmSaveLayerInfosOK(SaveLayersModel model) {
        List<SaveLayerInfo> layerInfos = model.getLayersWithConflictsAndUploadRequest();
        if (!layerInfos.isEmpty()) {
            warnLayersWithConflictsAndUploadRequest(layerInfos);
            return false;
        }

        layerInfos = model.getLayersWithoutFilesAndSaveRequest();
        if (!layerInfos.isEmpty()) {
            warnLayersWithoutFilesAndSaveRequest(layerInfos);
            return false;
        }

        layerInfos = model.getLayersWithIllegalFilesAndSaveRequest();
        if (!layerInfos.isEmpty()) {
            warnLayersWithIllegalFilesAndSaveRequest(layerInfos);
            return false;
        }

        return true;
    }

    protected void setUserAction(UserAction action) {
        this.action = action;
    }

    /**
     * Closes this dialog and frees all native screen resources.
     */
    public void closeDialog() {
        setVisible(false);
        saveSessionAction.destroy();
        dispose();
    }

    class WindowClosingAdapter extends WindowAdapter {
        @Override
        public void windowClosing(WindowEvent e) {
            cancelAction.cancel();
        }
    }

    class CancelAction extends AbstractAction {
        CancelAction() {
            putValue(NAME, tr("Cancel"));
            putValue(SHORT_DESCRIPTION, tr("Close this dialog and resume editing in JOSM"));
            new ImageProvider("cancel").getResource().attachImageIcon(this, true);
            InputMapUtils.addEscapeAction(getRootPane(), this);
        }

        protected void cancelWhenInEditingModel() {
            setUserAction(UserAction.CANCEL);
            closeDialog();
        }

        public void cancel() {
            switch (model.getMode()) {
            case EDITING_DATA: cancelWhenInEditingModel();
                break;
            case UPLOADING_AND_SAVING: cancelSafeAndUploadTask();
                break;
            }
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            cancel();
        }
    }

    class DiscardAndProceedAction extends AbstractAction implements PropertyChangeListener {
        DiscardAndProceedAction() {
            initForReason(Reason.EXIT);
        }

        public void initForReason(Reason reason) {
            switch (reason) {
                case EXIT:
                    putValue(NAME, tr("Exit now!"));
                    putValue(SHORT_DESCRIPTION, tr("Exit JOSM without saving. Unsaved changes are lost."));
                    new ImageProvider("exit").getResource().attachImageIcon(this, true);
                    break;
                case RESTART:
                    putValue(NAME, tr("Restart now!"));
                    putValue(SHORT_DESCRIPTION, tr("Restart JOSM without saving. Unsaved changes are lost."));
                    new ImageProvider("restart").getResource().attachImageIcon(this, true);
                    break;
                case DELETE:
                    putValue(NAME, tr("Delete now!"));
                    putValue(SHORT_DESCRIPTION, tr("Delete layers without saving. Unsaved changes are lost."));
                    new ImageProvider("dialogs", "delete").getResource().attachImageIcon(this, true);
                    break;
            }
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            setUserAction(UserAction.PROCEED);
            closeDialog();
        }

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            if (evt.getPropertyName().equals(SaveLayersModel.MODE_PROP)) {
                Mode mode = (Mode) evt.getNewValue();
                switch (mode) {
                case EDITING_DATA: setEnabled(true);
                    break;
                case UPLOADING_AND_SAVING: setEnabled(false);
                    break;
                }
            }
        }
    }

    class SaveSessionButtonAction extends JosmAction {

        SaveSessionButtonAction() {
            super(tr("Save Session"), "session", SessionSaveAction.getTooltip(), null, false, null, false);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            try {
                if (SessionSaveAction.getInstance().saveSession(false, true)) {
                    setUserAction(UserAction.PROCEED);
                    closeDialog();
                }
            } catch (UserCancelException userCancelException) {
                Logging.trace(userCancelException);
            }
        }
    }

    final class SaveAndProceedAction extends AbstractAction implements PropertyChangeListener {

        private ImageResource actionImg;

        SaveAndProceedAction() {
            initForReason(Reason.EXIT);
        }

        ImageResource getImage(String name, boolean disabled) {
            return new ImageProvider(name).setDisabled(disabled).setOptional(true).getResource();
        }

        public void initForReason(Reason reason) {
            switch (reason) {
                case EXIT:
                    putValue(NAME, tr("Perform actions before exiting"));
                    putValue(SHORT_DESCRIPTION, tr("Exit JOSM with saving. Unsaved changes are uploaded and/or saved."));
                    actionImg = new ImageProvider("exit").getResource();
                    break;
                case RESTART:
                    putValue(NAME, tr("Perform actions before restarting"));
                    putValue(SHORT_DESCRIPTION, tr("Restart JOSM with saving. Unsaved changes are uploaded and/or saved."));
                    actionImg = new ImageProvider("restart").getResource();
                    break;
                case DELETE:
                    putValue(NAME, tr("Perform actions before deleting"));
                    putValue(SHORT_DESCRIPTION, tr("Save/Upload layers before deleting. Unsaved changes are not lost."));
                    actionImg = new ImageProvider("dialogs", "delete").getResource();
                    break;
            }
            redrawIcon();
        }

        public void redrawIcon() {
            ImageResource uploadImg = model.getLayersToUpload().isEmpty() ? getImage("upload", true) : getImage("upload", false);
            ImageResource saveImg = model.getLayersToSave().isEmpty() ? getImage("save", true) : getImage("save", false);
            attachImageIcon(SMALL_ICON, ImageSizes.SMALLICON, uploadImg, saveImg, actionImg);
            attachImageIcon(LARGE_ICON_KEY, ImageSizes.LARGEICON, uploadImg, saveImg, actionImg);
        }

        private void attachImageIcon(String key, ImageSizes size, ImageResource uploadImg, ImageResource saveImg, ImageResource actionImg) {
            Dimension dim = size.getImageDimension();
            BufferedImage newIco = new BufferedImage(((int) dim.getWidth())*3, (int) dim.getHeight(), BufferedImage.TYPE_4BYTE_ABGR);
            Graphics2D g = newIco.createGraphics();
            drawImageIcon(g, 0, dim, uploadImg);
            drawImageIcon(g, 1, dim, saveImg);
            drawImageIcon(g, 2, dim, actionImg);
            putValue(key, new ImageIcon(newIco));
        }

        private void drawImageIcon(Graphics2D g, int index, Dimension dim, ImageResource img) {
            if (img != null) {
                g.drawImage(img.getImageIcon(dim).getImage(),
                        ((int) dim.getWidth())*index, 0, (int) dim.getWidth(), (int) dim.getHeight(), null);
            }
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (!confirmSaveLayerInfosOK(model))
                return;
            launchSafeAndUploadTask();
        }

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            if (evt.getPropertyName().equals(SaveLayersModel.MODE_PROP)) {
                SaveLayersModel.Mode mode = (SaveLayersModel.Mode) evt.getNewValue();
                switch (mode) {
                case EDITING_DATA: setEnabled(true);
                    break;
                case UPLOADING_AND_SAVING: setEnabled(false);
                    break;
                }
            }
        }
    }

    /**
     * This is the asynchronous task which uploads modified layers to the server and
     * saves them to files, if requested by the user.
     *
     */
    protected class SaveAndUploadTask implements Runnable {

        private final SaveLayersModel model;
        private final ProgressMonitor monitor;
        private final ExecutorService worker;
        private boolean canceled;
        private AbstractIOTask currentTask;

        public SaveAndUploadTask(SaveLayersModel model, ProgressMonitor monitor) {
            this.model = model;
            this.monitor = monitor;
            this.worker = Executors.newSingleThreadExecutor(Utils.newThreadFactory(getClass() + "-%d", Thread.NORM_PRIORITY));
        }

        protected void uploadLayers(List<SaveLayerInfo> toUpload) {
            for (final SaveLayerInfo layerInfo: toUpload) {
                AbstractModifiableLayer layer = layerInfo.getLayer();
                if (canceled) {
                    GuiHelper.runInEDTAndWait(() -> model.setUploadState(layer, UploadOrSaveState.CANCELED));
                    continue;
                }
                GuiHelper.runInEDTAndWait(() -> monitor.subTask(tr("Preparing layer ''{0}'' for upload ...", layerInfo.getName())));

                // checkPreUploadConditions must not be run in the EDT to avoid deadlocks
                if (!UploadAction.checkPreUploadConditions(layer)) {
                    GuiHelper.runInEDTAndWait(() -> model.setUploadState(layer, UploadOrSaveState.FAILED));
                    continue;
                }

                GuiHelper.runInEDTAndWait(() -> uploadLayersUploadModelStateOnFinish(layer));
                currentTask = null;
            }
        }

        /**
         * Update the {@link #model} state on upload finish
         * @param layer The layer that has been saved
         */
        private void uploadLayersUploadModelStateOnFinish(AbstractModifiableLayer layer) {
            AbstractUploadDialog dialog = layer.getUploadDialog();
            if (dialog != null) {
                dialog.setVisible(true);
                if (dialog.isCanceled()) {
                    model.setUploadState(layer, UploadOrSaveState.CANCELED);
                    return;
                }
                dialog.rememberUserInput();
            }

            currentTask = layer.createUploadTask(monitor);
            if (currentTask == null) {
                model.setUploadState(layer, UploadOrSaveState.FAILED);
                return;
            }
            Future<?> currentFuture = worker.submit(currentTask);
            try {
                // wait for the asynchronous task to complete
                currentFuture.get();
            } catch (CancellationException e) {
                Logging.trace(e);
                model.setUploadState(layer, UploadOrSaveState.CANCELED);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Logging.error(e);
                model.setUploadState(layer, UploadOrSaveState.FAILED);
                ExceptionDialogUtil.explainException(e);
            } catch (ExecutionException e) {
                Logging.error(e);
                model.setUploadState(layer, UploadOrSaveState.FAILED);
                ExceptionDialogUtil.explainException(e);
            }
            if (currentTask.isCanceled()) {
                model.setUploadState(layer, UploadOrSaveState.CANCELED);
            } else if (currentTask.isFailed()) {
                Logging.error(currentTask.getLastException());
                ExceptionDialogUtil.explainException(currentTask.getLastException());
                model.setUploadState(layer, UploadOrSaveState.FAILED);
            } else {
                model.setUploadState(layer, UploadOrSaveState.OK);
            }
        }

        protected void saveLayers(List<SaveLayerInfo> toSave) {
            for (final SaveLayerInfo layerInfo: toSave) {
                if (canceled) {
                    model.setSaveState(layerInfo.getLayer(), UploadOrSaveState.CANCELED);
                    continue;
                }
                // Check save preconditions earlier to avoid a blocking reentring call to EDT (see #10086)
                if (layerInfo.isDoCheckSaveConditions()) {
                    if (!layerInfo.getLayer().checkSaveConditions()) {
                        continue;
                    }
                    layerInfo.setDoCheckSaveConditions(false);
                }
                currentTask = new SaveLayerTask(layerInfo, monitor);
                Future<?> currentFuture = worker.submit(currentTask);

                try {
                    // wait for the asynchronous task to complete
                    //
                    currentFuture.get();
                } catch (CancellationException e) {
                    Logging.trace(e);
                    model.setSaveState(layerInfo.getLayer(), UploadOrSaveState.CANCELED);
                } catch (InterruptedException | ExecutionException e) {
                    Logging.error(e);
                    model.setSaveState(layerInfo.getLayer(), UploadOrSaveState.FAILED);
                    ExceptionDialogUtil.explainException(e);
                }
                if (currentTask.isCanceled()) {
                    model.setSaveState(layerInfo.getLayer(), UploadOrSaveState.CANCELED);
                } else if (currentTask.isFailed()) {
                    if (currentTask.getLastException() != null) {
                        Logging.error(currentTask.getLastException());
                        ExceptionDialogUtil.explainException(currentTask.getLastException());
                    }
                    model.setSaveState(layerInfo.getLayer(), UploadOrSaveState.FAILED);
                } else {
                    model.setSaveState(layerInfo.getLayer(), UploadOrSaveState.OK);
                }
                this.currentTask = null;
            }
        }

        protected void warnBecauseOfUnsavedData() {
            int numProblems = model.getNumCancel() + model.getNumFailed();
            if (numProblems == 0)
                return;
            Logging.warn(numProblems + " problems occurred during upload/save");
            String msg = trn(
                    "<html>An upload and/or save operation of one layer with modifications<br>"
                    + "was canceled or has failed.</html>",
                    "<html>Upload and/or save operations of {0} layers with modifications<br>"
                    + "were canceled or have failed.</html>",
                    numProblems,
                    numProblems
            );
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    msg,
                    tr("Incomplete upload and/or save"),
                    JOptionPane.WARNING_MESSAGE
            );
        }

        @Override
        public void run() {
            GuiHelper.runInEDTAndWait(() -> model.setMode(SaveLayersModel.Mode.UPLOADING_AND_SAVING));
            // We very specifically do not want to block the EDT or the worker thread when validating
            List<SaveLayerInfo> toUpload = model.getLayersToUpload();
            if (!toUpload.isEmpty()) {
                uploadLayers(toUpload);
            }
            GuiHelper.runInEDTAndWait(() -> {
                List<SaveLayerInfo> toSave = model.getLayersToSave();
                if (!toSave.isEmpty()) {
                    saveLayers(toSave);
                }
                model.setMode(SaveLayersModel.Mode.EDITING_DATA);
                if (model.hasUnsavedData()) {
                    warnBecauseOfUnsavedData();
                    model.setMode(Mode.EDITING_DATA);
                    if (canceled) {
                        setUserAction(UserAction.CANCEL);
                        closeDialog();
                    }
                } else {
                    setUserAction(UserAction.PROCEED);
                    closeDialog();
                }
            });
            worker.shutdownNow();
        }

        public void cancel() {
            if (currentTask != null) {
                currentTask.cancel();
            }
            worker.shutdown();
            canceled = true;
        }
    }

    @Override
    public void tableChanged(TableModelEvent e) {
        boolean dis = model.getLayersToSave().isEmpty() && model.getLayersToUpload().isEmpty();
        if (saveAndProceedActionButton != null) {
            saveAndProceedActionButton.setEnabled(!dis);
        }
        saveAndProceedAction.redrawIcon();
    }
}
