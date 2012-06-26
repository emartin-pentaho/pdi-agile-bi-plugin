/*
 * This program is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License, version 2.1 as published by the Free Software
 * Foundation.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this
 * program; if not, you can obtain a copy at http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
 * or from the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * Copyright (c) 2009 Pentaho Corporation..  All rights reserved.
 */
package org.pentaho.agilebi.spoon;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;

import org.apache.commons.io.IOUtils;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.pentaho.agilebi.modeler.IModelerSource;
import org.pentaho.agilebi.modeler.ModelerException;
import org.pentaho.agilebi.modeler.ModelerMessagesHolder;
import org.pentaho.agilebi.modeler.ModelerWorkspace;
import org.pentaho.agilebi.modeler.util.ModelerWorkspaceUtil;
import org.pentaho.agilebi.modeler.util.TableModelerSource;
import org.pentaho.agilebi.spoon.perspective.AgileBiModelerPerspective;
import org.pentaho.agilebi.spoon.visualizations.IVisualization;
import org.pentaho.agilebi.spoon.visualizations.VisualizationManager;
import org.pentaho.agilebi.spoon.wizard.EmbeddedWizard;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.gui.SpoonFactory;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.repository.Repository;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.di.trans.step.TableFrontingStep;
import org.pentaho.di.trans.steps.tableoutput.TableOutputMeta;
import org.pentaho.di.ui.core.database.dialog.DatabaseExplorerDialog;
import org.pentaho.di.ui.core.dialog.ErrorDialog;
import org.pentaho.di.ui.spoon.ISpoonMenuController;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.di.ui.spoon.SpoonPerspectiveManager;
import org.pentaho.di.ui.spoon.TabMapEntry;
import org.pentaho.metadata.model.Domain;
import org.pentaho.metadata.registry.Entity;
import org.pentaho.metadata.registry.IMetadataRegistry;
import org.pentaho.metadata.registry.Link;
import org.pentaho.metadata.registry.RegistryFactory;
import org.pentaho.metadata.registry.SimpleFileRegistry;
import org.pentaho.metadata.registry.Type;
import org.pentaho.metadata.registry.Verb;
import org.pentaho.reporting.engine.classic.core.ClassicEngineBoot;
import org.pentaho.reporting.libraries.base.util.ObjectUtilities;
import org.pentaho.ui.xul.XulException;
import org.pentaho.ui.xul.components.WaitBoxRunnable;
import org.pentaho.ui.xul.components.XulWaitBox;
import org.pentaho.ui.xul.dom.Document;
import org.pentaho.ui.xul.impl.AbstractXulEventHandler;
import org.pentaho.xul.swt.tab.TabItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModelerHelper extends AbstractXulEventHandler implements ISpoonMenuController {

  private static final String MODELER_NAME = "Modeler"; 
  private static final String TEMP_MODELS_FOLDER = "models";

  private static ModelerHelper instance = null;
    
  private static Logger logger = LoggerFactory.getLogger(ModelerHelper.class);

  static{

    try {
      ModelerMessagesHolder.setMessages(new SpoonModelerMessages());
      
      RegistryFactory factory = RegistryFactory.getInstance();
      IMetadataRegistry registry = factory.getMetadataRegistry();
      if( registry == null ) {
          try {
        	  registry = new SimpleFileRegistry();
        	  ((SimpleFileRegistry) registry).setFilePath("registry.xml"); //$NON-NLS-1$
			factory.setMetadataRegistry(registry);
			registry.init();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
      }
      
    } catch (IllegalStateException e) {
      // someone else set this first, ignore this error
    }
  }
  private ModelerHelper() {
    File modelsDir = new File(TEMP_MODELS_FOLDER);
    if(modelsDir.exists()) {
      for(File file : modelsDir.listFiles()) {
        file.delete();
      }
      modelsDir.delete();
    }
  }
  
  public static ModelerHelper getInstance() {
    if( instance == null ) {
      instance = new ModelerHelper();
      Spoon spoon = ((Spoon)SpoonFactory.getInstance());
      spoon.addSpoonMenuController(instance);
    }
    return instance;
  }

  /**
   * this method is used to see if a valid TableOutput step has been
   * selected in a trans graph before attempting to model or quick vis
   *
   * @return true if valid
   */
  public static boolean isValidStepSelected() {
    Spoon spoon = ((Spoon)SpoonFactory.getInstance());
    TransMeta transMeta = spoon.getActiveTransformation();
    if( transMeta == null || spoon.getActiveTransGraph() == null ) {
      return false;
    }
    StepMeta stepMeta = spoon.getActiveTransGraph().getCurrentStep();
    if(stepMeta != null && !(stepMeta.getStepMetaInterface() instanceof TableOutputMeta) ) {
      return true;
    }
    // see if we can get the objects we need
    StepMetaInterface smi = stepMeta.getStepMetaInterface();
    
    if( smi instanceof TableFrontingStep ) {
    	return true;
    }
    
    DatabaseConnectionMethods connectionMethods = getDatabaseConnectionMethods(smi);
    
	boolean gotDatabaseMeta = connectionMethods.getDatabaseMetaMethod() != null;
	boolean gotTableName = connectionMethods.getTableNameMethod() != null;
    return gotDatabaseMeta && gotTableName;
  }

  public static DatabaseConnectionMethods getDatabaseConnectionMethods(StepMetaInterface smi) {
	  DatabaseConnectionMethods connectionMethods = new DatabaseConnectionMethods();
	  
	  if( smi instanceof TableFrontingStep ) {
		  TableFrontingStep step = (TableFrontingStep) smi;
		  connectionMethods.setDatabaseMeta(step.getDatabaseMeta());
		  connectionMethods.setTableName(step.getTableName());
		  connectionMethods.setSchemaName(step.getSchemaName());
		  return connectionMethods;
	  }
	  
		Method methods[] = smi.getClass().getMethods();
		for( Method m: methods) {
			if( m.getName().equalsIgnoreCase("getDatabaseMeta")) {
				connectionMethods.setDatabaseMetaMethod(m);
				try {
					DatabaseMeta databaseMeta = (DatabaseMeta) m.invoke(smi);
					connectionMethods.setDatabaseMeta(databaseMeta);
				} catch (Exception e) {
					// we are just introspecting
				}
			}
			if( m.getName().equalsIgnoreCase("getTableName")) {
				connectionMethods.setTableNameMethod(m);
				try {
					String tableName = (String) m.invoke(smi);
					connectionMethods.setTableName(tableName);
				} catch (Exception e) {
					// we are just introspecting
				}
			}
			if( m.getName().equalsIgnoreCase("getSchemaName")) {
				connectionMethods.setSchemaNameMethod(m);
				try {
					String schemaName = (String) m.invoke(smi);
					connectionMethods.setSchemaName(schemaName);
				} catch (Exception e) {
					// we are just introspecting
				}
			}
		}
		
		return connectionMethods;
  }
  
  public static ModelerWorkspace populateModelFromOutputStep(ModelerWorkspace model) throws ModelerException {

    Spoon spoon = ((Spoon)SpoonFactory.getInstance());
    TransMeta transMeta = spoon.getActiveTransformation();
    if( transMeta == null || spoon.getActiveTransGraph() == null ) {
      SpoonFactory.getInstance().messageBox( BaseMessages.getString(ModelerWorkspace.class,  "ModelerWorkspaceUtil.FromOutputStep.TransNotOpen" ), MODELER_NAME, false, Const.ERROR); //$NON-NLS-1$
      throw new IllegalStateException(BaseMessages.getString(ModelerWorkspace.class,  "ModelerWorkspaceUtil.FromOutputStep.TransNotOpen")); //$NON-NLS-1$
    }
    StepMeta stepMeta = spoon.getActiveTransGraph().getCurrentStep();

    // do it by introspection
    StepMetaInterface smi = stepMeta.getStepMetaInterface();
        
    DatabaseConnectionMethods connectionMethods = getDatabaseConnectionMethods(smi);
    DatabaseMeta databaseMeta = connectionMethods.getDatabaseMeta();
    String tableName = connectionMethods.getTableName();
    String schemaName = connectionMethods.getSchemaName();
    
		// we must have a database meta and a table name to continue, schema is optional
    if( databaseMeta == null || tableName == null ) {
      SpoonFactory.getInstance().messageBox( BaseMessages.getString(ModelerWorkspace.class,  "ModelerWorkspaceUtil.FromOutputStep.StepNeeded"), MODELER_NAME, false, Const.ERROR); //$NON-NLS-1$
      throw new IllegalStateException(BaseMessages.getString(ModelerWorkspace.class,  "ModelerWorkspaceUtil.FromOutputStep.OutputStepNeeded")); //$NON-NLS-1$
    }

    RowMetaInterface rowMeta = null;
    try {
      rowMeta = transMeta.getStepFields(stepMeta);
    } catch (KettleException e) {
    	logger.info("Error getting step fields", e);
    	Throwable rootCause = e;
    	while (rootCause.getCause() != null && rootCause != rootCause.getCause()) {
    	  rootCause = rootCause.getCause();
    	}
      throw new ModelerException(BaseMessages.getString(ModelerWorkspace.class,  "ModelerWorkspaceUtil.FromOutputStep.NoStepMeta"), rootCause); //$NON-NLS-1$
    }
    if(rowMeta == null){
   	 throw new ModelerException(BaseMessages.getString(ModelerWorkspace.class,  "ModelerWorkspaceUtil.FromOutputStep.NoStepMeta")); //$NON-NLS-1$
    }


    OutputStepModelerSource source = new OutputStepModelerSource(tableName, schemaName, databaseMeta);
    source.setFileName(transMeta.getFilename());
    source.setStepId(stepMeta.getStepID());
    Repository repository = transMeta.getRepository();
    if(repository != null) {
    	source.setRepositoryName(repository.getName());
    }
    Domain d = source.generateDomain();


    model.setModelSource(source);
    model.setModelName(tableName);
    model.setDomain(d);

    RegistryFactory factory = RegistryFactory.getInstance();
    IMetadataRegistry registry = factory.getMetadataRegistry();
    Entity tableEntity = new Entity(databaseMeta.getName()+"~"+schemaName+"~"+tableName, tableName, Type.TYPE_PHYSICAL_TABLE.getId());
    String transFileName = Spoon.getInstance().getActiveTransformation().getFilename();
    Entity transEntity = new Entity(transFileName, Spoon.getInstance().getActiveTransformation().getName(), Type.TYPE_TRANSFORMATION.getId());
    registry.addEntity(transEntity);
    registry.addEntity(tableEntity);
    Link link = new Link( transEntity, Verb.VERB_POPULATES, tableEntity );
    registry.addLink(link);
    try {
		registry.commit();
	} catch (Exception e) {
		logger.error("Could not commit metadata registry", e);
	}
    
    return model;
  }
  
  
  public void createModelerTabFromOutputStep() throws ModelerException {
    Spoon spoon = ((Spoon)SpoonFactory.getInstance());

    ModelerWorkspace model = createModelerWorkspace();
    
    populateModelFromOutputStep(model);
    
    AgileBiModelerPerspective.getInstance().createTabForModel(model, MODELER_NAME);
    
  }
  
  public void createModelerTabFromSource( IModelerSource source ) throws ModelerException {

    Spoon spoon = ((Spoon)SpoonFactory.getInstance());

    ModelerWorkspace model = createModelerWorkspace();
    model.setModelSource(source);
    ModelerWorkspaceUtil.populateModelFromSource(model, source);
    
    // create unique name
    AgileBiModelerPerspective.getInstance().createTabForModel(model, getUniqueUntitledTabName(spoon, MODELER_NAME));
  }

  // TODO: replace this code after M1
  public Domain loadDomain(String fname){
    try{
      ModelerWorkspace model = createModelerWorkspace();
      String xml = new String(IOUtils.toByteArray(new FileInputStream(new File(fname))), "UTF-8"); //$NON-NLS-1$
      ModelerWorkspaceUtil.loadWorkspace(fname, xml, model);
      return model.getDomain();
    } catch(ModelerException e){
      e.printStackTrace();
    } catch(IOException e){
      e.printStackTrace();
    }
    return null;
  }
  
  
  private String getUniqueUntitledTabName(Spoon spoon, String title) {
    int num = 1;
    String tabName = title + " " + num; //$NON-NLS-1$
    // TODO: Add new plugin object type to spoon
    TabItem tabItem = spoon.delegates.tabs.findTabMapEntry(tabName, TabMapEntry.ObjectType.BROWSER).getTabItem();
    while (tabItem != null) {
      tabName = title + " " + (++num); //$NON-NLS-1$
      // TODO: Add new plugin object type to spoon
      tabItem = spoon.delegates.tabs.findTabMapEntry(tabName, TabMapEntry.ObjectType.BROWSER).getTabItem();
    }
    return tabName;
  }

  public String getName(){
    return "agileBi"; //$NON-NLS-1$
  }
  
  public void openModeler() {
    if (!isValidStepSelected()) {
      return;
    }

    try{
      ModelerHelper.getInstance().createModelerTabFromOutputStep();
      SpoonPerspectiveManager.getInstance().activatePerspective(AgileBiModelerPerspective.class);
      
    } catch(Exception e){
      logger.error("Error opening modeler", e);
      new ErrorDialog(((Spoon) SpoonFactory.getInstance()).getShell(), "Error", "Error creating modeler", e); 
    }
  }
  
  public void quickVisualizeTable() {
    Spoon spoon = ((Spoon)SpoonFactory.getInstance());
    if( spoon.getSelectionObject() instanceof DatabaseMeta ) {
      final DatabaseMeta databaseMeta = (DatabaseMeta) spoon.getSelectionObject();
      
      DatabaseExplorerDialog std = new DatabaseExplorerDialog(spoon.getShell(), SWT.NONE, databaseMeta, new ArrayList<DatabaseMeta>());
      if (std.open()) {
          
        TableModelerSource source = new TableModelerSource( databaseMeta, std.getTableName(), std.getSchemaName() == null ? "" : std.getSchemaName() ); //$NON-NLS-1$
        if( source.getSchemaName() == null ) {
          source.setSchemaName(""); //$NON-NLS-1$
        }

        try{
          ModelerWorkspace model = createModelerWorkspace();
          ModelerWorkspaceUtil.populateModelFromSource(model, source);
          quickVisualize( model );
        } catch(Exception e){
          logger.error("Error opening visualization", e);
          new ErrorDialog(((Spoon) SpoonFactory.getInstance()).getShell(), "Error", "Error executing Quick Visualize", e);
        }
      }
    }
  }

  public void quickVisualizeTableOutputStep() {
    if (!isValidStepSelected()) {
      return;
    }

    try{
      ModelerWorkspace model = createModelerWorkspace();
      populateModelFromOutputStep(model);
      quickVisualize( model );
    } catch(Exception e){
      logger.error("Error visualizing", e);
      new ErrorDialog(((Spoon) SpoonFactory.getInstance()).getShell(), "Error", "Error creating visualization", e);
    }

  }
  
  public void reportWizard() {
    if (!isValidStepSelected()) {
      return;
    }
    
    XulWaitBox box;
    try {
      box = (XulWaitBox) document.createElement("waitbox");
      box.setIndeterminate(true);
      box.setCanCancel(false);
      box.setTitle(BaseMessages.getString(ModelerWorkspace.class, "wait_dialog_title"));
      box.setMessage(BaseMessages.getString(ModelerWorkspace.class, "wait_dialog_message"));
      
      box.setCancelLabel(BaseMessages.getString(ModelerWorkspace.class, "wait_dialog_btn"));
      
      box.setDialogParent(((Spoon)SpoonFactory.getInstance()).getShell());
      box.setRunnable(new WaitBoxRunnable(box){
        boolean canceled = false;
        @Override
        public void run() {
          
          try {
            ModelerWorkspace model = createModelerWorkspace();
            populateModelFromOutputStep(model);

            ObjectUtilities.setClassLoader(getClass().getClassLoader());
            ObjectUtilities.setClassLoaderSource(ObjectUtilities.CLASS_CONTEXT);
            
            if(ClassicEngineBoot.getInstance().isBootDone() == false){
              ClassicEngineBoot engineBoot = ClassicEngineBoot.getInstance();
              engineBoot.start();
            }
            createTemporaryModel(model, true, true);
            EmbeddedWizard wizard = new EmbeddedWizard(model, true);
            waitBox.stop();
            wizard.run(null);
          } catch (final Exception e) {
            logger.error("Error booting reporting engine", e);
            Display.getDefault().asyncExec(new Runnable(){
              public void run() {
                new ErrorDialog(((Spoon) SpoonFactory.getInstance()).getShell(), "Error", "Error creating visualization", e);
              }
            });
          
          }
          waitBox.stop();
        }

        @Override
        public void cancel() {
          canceled =true;
        }
        
        
      });
      box.start();
    } catch (XulException e1) {
      logger.error("error creating PRPT", e1);
      new ErrorDialog(((Spoon) SpoonFactory.getInstance()).getShell(), "Error", "Error creating visualization", e1);
    }


  }
  
  public void quickVisualize( ModelerWorkspace model, String name, String fileName ) throws ModelerException {
	    createTemporaryModel(model, true, true);
	    VisualizationManager theManager = VisualizationManager.getInstance();
	    IVisualization theVisualization = theManager.getVisualization(theManager.getVisualizationNames().get(0));
	    if(theVisualization != null) {
	      if (model.getFileName() != null) {
	        // TODO: Find a better name for the cube, maybe just model name?
	        theVisualization.createVisualizationFromModel(model, true);
	        Spoon.getInstance().enableMenus();
	      } else {
	        throw new UnsupportedOperationException("TODO: prompt to save model before visualization");
	      }
	    }
	  }

  public void quickVisualize( ModelerWorkspace model ) throws ModelerException {
	    createTemporaryModel(model, true, true);
	    VisualizationManager theManager = VisualizationManager.getInstance();
	    IVisualization theVisualization = theManager.getVisualization(theManager.getVisualizationNames().get(0));
	    if(theVisualization != null) {
	      if (model.getFileName() != null) {
	        // TODO: Find a better name for the cube, maybe just model name?
	        theVisualization.createVisualizationFromModel(model, true);
	        Spoon.getInstance().enableMenus();
	      } else {
	        throw new UnsupportedOperationException("TODO: prompt to save model before visualization");
	      }
	    }
	  }

  public String createTemporaryModel(ModelerWorkspace model, boolean saveName, boolean autoModel) throws ModelerException {
    //give it a temporary name
    File modelsDir = new File(TEMP_MODELS_FOLDER); //$NON-NLS-1$
    modelsDir.mkdirs();
    int idx = 1;
    boolean looking = true;
    File modelFile;
    String fileName = ""; //$NON-NLS-1$
    String modelName = ""; //$NON-NLS-1$
    while( looking ) {
      modelName = "Model "+idx; //$NON-NLS-1$
      fileName = TEMP_MODELS_FOLDER + "/" + modelName+".xmi"; //$NON-NLS-1$ //$NON-NLS-2$
      modelFile = new File(fileName);
      if( !modelFile.exists() ) {
        looking = false;
      }
      idx++;
    }
    if(saveName){
      model.setFileName(fileName);
    }
    model.setModelName(modelName);
    if(autoModel){
      model.getWorkspaceHelper().autoModelFlat(model);
    }
    model.getWorkspaceHelper().populateDomain(model);
    ModelerWorkspaceUtil.saveWorkspace( model, fileName);
    
    /*
    // link the model to the transformation
    RegistryFactory factory = RegistryFactory.getInstance();
    IMetadataRegistry registry = factory.getMetadataRegistry();
    Entity modelEntity = new Entity(fileName, model.getModelName(), RegistryBase.TYPE_OLAP_MODEL.getId());
    Entity transEntity = new Entity(transFileName, name, RegistryBase.TYPE_TRANSFORMATION.getId());
    registry.addEntity(transEntity.getId(), transEntity);
    registry.addEntity(modelEntity.getId(), modelEntity);
//    Link link = new Link(modelEntity, null, transEntity); 
 */
    return fileName;
  }
  
  public ModelerWorkspace clone(ModelerWorkspace model) throws ModelerException{
    String fileName = createTemporaryModel(model, false, false);
    
    ModelerWorkspace newModel = createModelerWorkspace();
    
    newModel.setTemporary(true);
    newModel.setDirty(false);
    String xml;
    try {
      xml = new String(IOUtils.toByteArray(new FileInputStream(new File(fileName))), "UTF-8");
      ModelerWorkspaceUtil.loadWorkspace(fileName, xml, newModel);
    } catch (Exception e) {
      throw new ModelerException(e);
    } //$NON-NLS-1$
    return newModel;
  }
  
  public void databaseModelItem() {
    Spoon spoon = ((Spoon)SpoonFactory.getInstance());
    if( spoon.getSelectionObject() instanceof DatabaseMeta ) {
      final DatabaseMeta databaseMeta = (DatabaseMeta) spoon.getSelectionObject();
      
      DatabaseExplorerDialog std = new DatabaseExplorerDialog(spoon.getShell(), SWT.NONE, databaseMeta, new ArrayList<DatabaseMeta>());
      if (std.open()) {
          
        TableModelerSource source = new TableModelerSource( databaseMeta, std.getTableName(), std.getSchemaName());
        try{
          ModelerWorkspace model = createModelerWorkspace();
          ModelerWorkspaceUtil.populateModelFromSource(model, source);
          AgileBiModelerPerspective.getInstance().createTabForModel(model, getUniqueUntitledTabName(spoon, MODELER_NAME));
        } catch(Exception e){
          logger.error("Error creating model", e);
          new ErrorDialog(((Spoon) SpoonFactory.getInstance()).getShell(), "Error", "Error creating Modeler", e);
        }
      }
    }
  }  
  
  public void createEmptyModel() {
    try {
      ModelerWorkspace model = createModelerWorkspace();
      AgileBiModelerPerspective.getInstance().createTabForModel(model, MODELER_NAME);
      SpoonPerspectiveManager.getInstance().activatePerspective(AgileBiModelerPerspective.class);
    } catch (Exception e) {
      new ErrorDialog(((Spoon) SpoonFactory.getInstance()).getShell(), "Error", "Error creating visualization", e);
    }
  }

  private ModelerWorkspace createModelerWorkspace() {
    return new ModelerWorkspace(new SpoonModelerWorkspaceHelper(), SpoonModelerWorkspaceHelper.initGeoContext());
  }

  public void updateMenu(Document doc) {
	
//	  boolean isDisabled = !isValidStepSelected();
//	  XulComponent menuItem = getXulDomContainer().getDocumentRoot().getElementById("trans-graph-entry-model");
//	  if (menuItem != null) {
//		  menuItem.setDisabled(isDisabled);
//	  }
//
//	  menuItem = getXulDomContainer().getDocumentRoot().getElementById("trans-graph-entry-visualize");
//	  if (menuItem != null) {
//		  menuItem.setDisabled(isDisabled);
//	  }
  }
}
