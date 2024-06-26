package org.smap.sdal.model;

import java.util.ArrayList;
import java.util.Collection;
import org.smap.sdal.Utilities.GeneralUtilityMethods;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/*
 * Form Class
 * Used for survey editing
 */
public class Question {
	public int id;
	public int fId;
	public int formIndex;			// Used by the online editor when the formId is not known (for a question in a new form)
	public int childFormIndex;		// Set in online editor when creating a new sub form
	public String name;
	public String columnName;			// The name of the database column for this question
	public String display_name;
	public String type;
	public String text_id;
	public String hint_id;
	public int l_id;				// Id for listname identifying list of options
	public String list_name;		// A reference to the list of options 
	public int seq;
	public int sourceSeq;
	public int sourceFormId;		// The id as stored in the database for the form
	public int sourceFormIndex;		// Used when the source form in an move is new
	public String defaultanswer;
	public ArrayList<SetValue> setValues;
	public String appearance;
	public String app_choices;
	public ArrayList<KeyValueSimp>  paramArray;
	public String parameters;
	public String choice_filter;
	public String source;
	public String source_param;
	public String calculation;
	public ServerCalculation server_calculation;
	public String constraint;
	public String constraint_msg;
	public String relevant;
	public boolean visible;
	public boolean readonly;
	public String readonly_expression;
	public boolean required;				// Legacy
	public String required_expression;		// Probably this should contain true or false if the value is boolean
	public boolean compressed;
	public boolean propertyType = false;	// If set these questions will not be shown in the editor
	public boolean published;				// Set true if the question has been added to a results table
	public boolean soft_deleted = false;	// Set true if the question has been deleted but exists in results tables
	public boolean inMeta;					// Set true if the question is in the meta group
	int width = -1;							// Display width, generated from appearance column 
												//  (for the moment - should probably have its own column 
												//  entry but I want to maintain compatibility with xlsform)
	public String autoplay;
	public String accuracy;
	public String intent;
	public String linked_target;			//sId::qId of the target to link to
	public ArrayList<Label> labels = new ArrayList<Label> ();
	public String nodeset;
	public String dataType;
	public boolean external_choices;
	public String external_table;
	
	public String repeatCount;			// Temporary data used during xls processing	
	public boolean isTableList = false;	// Temporary flag used during HTML generation
	
	public String style_list;			// The name of the style applied to this question
	public String trigger;
	public int style_id;				// The id of the style as used in the database
	public int flash;					// flash interval for literacy questions
	
	public Question() {
		
	}
	
	// CLone a question
	// Note: the cloning is not complete but it is adequate for uploading matrix types
	public Question(Question q) {
		this.fId = q.fId;
		this.name = q.name;
		this.columnName = q.columnName;
		this.display_name = q.display_name;
		this.type = q.type;
		this.text_id = q.text_id;
		this.hint_id = q.hint_id;		
		this.l_id = q.l_id;
		this.list_name = q.list_name;
		this.defaultanswer = q.defaultanswer;	
		this.appearance = q.appearance;
		this.app_choices = q.app_choices;
		this.parameters = q.parameters;
		this.choice_filter = q.choice_filter;
		this.source = q.source;
		this.source_param = q.source_param;
		this.calculation = q.calculation;
		this.required = q.required;
		this.required_expression = q.required_expression;
		this.relevant = q.relevant;
		this.constraint = q.constraint;
		this.constraint_msg = q.constraint_msg;
		this.visible = q.visible;	
		this.readonly = q.readonly;
		this.readonly_expression = q.readonly_expression;
		this.required = q.required;
		this.compressed = q.compressed;
		this.autoplay = q.autoplay;
		this.accuracy = q.accuracy;
		this.intent = q.intent;
	}
	
	/*
	 * Return the required value
	 */
	public boolean isRequired() {
		return required;
	}
	
	/*
	 * Get the selectable choices for this question
	 *  If the choices came from an external file then one of the choices will be a dummy choice describing the file
	 *  in that case only return the choices marked as coming from an external file
	 */
	public Collection<Option> getValidChoices(Survey s) {
		
		ArrayList<Option> externalChoices = new ArrayList<Option> ();
		OptionList ol = s.surveyData.optionLists.get(list_name);
		ArrayList<Option> choiceArray = ol.options;
		if(choiceArray == null) {
			choiceArray = new ArrayList<Option> ();
		}
		boolean external = false;
		for(int i = 0; i < choiceArray.size(); i++) {
			if(choiceArray.get(i).externalFile) {
				external = true;
				externalChoices.add(choiceArray.get(i));
			}
		}
		if(external) {
			return externalChoices;
		} else {
			return choiceArray;
		}
	}
	
	/*
	 * Update the column settings if the appearance option in this question is set to pdfcols
	 *  Return null if this question does not change the column settings
	 */
	public int [] updateCols(int [] currentCols) {
		
		int [] newCols;
		int totalWidth = 0;
		
		if(appearance != null && appearance.contains("pdfcols")) {
			
			String [] appValues = appearance.split(" ");
			if(appearance != null) {
				for(int i = 0; i < appValues.length; i++) {
					if(appValues[i].startsWith("pdfcols")) {
						
						String [] parts = appValues[i].split("_");
						
						newCols = new int [parts.length - 1];
						for(int j = 1; j < parts.length; j++) {
							newCols[j - 1] = Integer.valueOf(parts[j]);
							totalWidth += newCols[j - 1];
						}
						if(totalWidth != 10) {
							newCols[newCols.length -1] += 10 - totalWidth;		// Make sure widths all add up to 10
						}
						
						if(newCols.length != currentCols.length) {
							return newCols;
						}
						
						for(int j = 0; j < newCols.length; j++) {
							if(newCols[j] != currentCols[j]) {
								return newCols;
							}
						}
						
						break;
					}
				}
			
			}
			
		}
		
		return null;

	}
	
	/*
	 * Return true if this question needs to be displayed on a new page in pdf reports
	 */
	public boolean isNewPage() {
		boolean newPage = false;
		
		if(appearance != null && appearance.contains("pdfnewpage")) {
			
			String [] appValues = appearance.split(" ");
			if(appearance != null) {
				for(int i = 0; i < appValues.length; i++) {
					if(appValues[i].equals("pdfnewpage")) {
						newPage = true;
						break;
					}
				}
			}
		}
		
		
		return newPage;
	}
	
	/*
	 * Return true if this is a preload question
	 */
	public boolean isPreload() {
		return GeneralUtilityMethods.isPropertyType(source_param, columnName);
	}
	
	/*
	 * Return true if this is a select question
	 */
	public boolean isSelect() {
		return (type.startsWith("select") || type.equals("rank"));
	}
	
	/*
	 * Add a set value
	 */
	public void addSetValue(String event, String value, String ref) {
		if(setValues == null) {
			setValues = new ArrayList<> ();
		}
		setValues.add(new SetValue(event, value, ref));
	}
	
	/*
	 * Get set values as String
	 */
	public String getSetValueArrayAsString(Gson gson) {
		if(setValues == null) {
			return null;
		}
		return gson.toJson(setValues);
	}
	
	/*
	 * Set the set value from a string
	 */
	public void setSetValue(Gson gson, String v) {
		if(v == null || v.trim().length() == 0) {
			setValues = null;
		} else {
			setValues = gson.fromJson(v, new TypeToken<ArrayList<SetValue>>() {}.getType());
		}
	}
	
	public String getDefaultSetValue() {
		String def = null;
		if(setValues != null && setValues.size() > 0) {
			for(SetValue sv : setValues) {
				if(sv.event.equals(SetValue.START)) {
					def = sv.value;
					break;
				}
			}
		}
		return def;
	}
}
