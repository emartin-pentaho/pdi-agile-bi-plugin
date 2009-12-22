/**
 * 
 */
package org.pentaho.agilebi.pdi.modeler;

import java.awt.Image;
import java.io.Serializable;

import org.pentaho.ui.xul.util.AbstractModelNode;

public class MeasuresCollection extends AbstractMetaDataModelNode<MeasureMetaData>  implements Serializable {
  private String name = "Measures";
  
  public String getName() {
    return name;
  }
  
  public void setName(String name) {
    this.name = name;
  }
  
  public boolean isUiExpanded(){
    return true;
  }

  @Override
  public String getValidImage() {
    return "images/sm_folder_icon.png"; //$NON-NLS-1$
  }

  @Override
  public void validate() {
    valid = true;
    validationMessages.clear();
    if (size() == 0) {
      validationMessages.add("Model requires at least one Measure");
      valid = false;
    }
  }

  @Override
  public void onAdd(MeasureMetaData field) {
    field.setParent(this);
  }
  
  public boolean isEditingDisabled(){
    return true;
  }
  
  @Override
  public Class<? extends ModelerNodePropertiesForm> getPropertiesForm() {
    return null;
  }
}