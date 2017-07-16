package org.smap.server.utilities;

import java.io.StringWriter;
import java.io.Writer;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Stack;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.smap.sdal.Utilities.GeneralUtilityMethods;
import org.smap.sdal.Utilities.SDDataSource;
import org.smap.sdal.managers.SurveyManager;
import org.smap.sdal.model.Form;
import org.smap.sdal.model.Label;
import org.smap.sdal.model.Language;
import org.smap.sdal.model.Option;
import org.smap.sdal.model.Question;
import org.smap.sdal.model.Survey;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/*
 * Return an HTML5 section built from a survey defined in the database
 * 
 * Translations are stored as a hierarchy of values from language down to element type (as below)
 *   languages (HashMap)-> translation id's (HashMap)-> translation types (HashMap) -> translation element (String)
 */
public class GetHtml {

	Survey survey = null;
	int languageIndex = 0;
	HashMap<String, String> paths = new HashMap<>(); // Keep paths out of the survey model and instead store them here
	Document outputDoc = null;
	
	private static Logger log = Logger.getLogger(GetHtml.class.getName());

	/*
	 * Get the Html as a string
	 */
	public String get(HttpServletRequest request, int sId, boolean superUser) {

		String response = null;

		// Get the base path
		String basePath = GeneralUtilityMethods.getBasePath(request);
		Connection sd = SDDataSource.getConnection("Get Html");
		SurveyManager sm = new SurveyManager();

		try {

			survey = sm.getById(sd, null, request.getRemoteUser(), sId, true, basePath, null, false, false, true, true,
					false, "real", superUser, 0, null);

			log.info("Getting survey as Html-------------------------------");
			// Create a new XML Document
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			DocumentBuilder b = dbf.newDocumentBuilder();
			outputDoc = b.newDocument();

			Writer outWriter = new StringWriter();
			Result outStream = new StreamResult(outWriter);

			Element parent;
			parent = populateRoot();
			// populateHead(sd, outputHtml, b, parent);
			createForm(parent, true, true);

			// Write the survey to a string and return it to the calling program
			Transformer transformer = TransformerFactory.newInstance().newTransformer();
			transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			transformer.setOutputProperty(OutputKeys.METHOD, "html");

			DOMSource source = new DOMSource(outputDoc);
			transformer.transform(source, outStream);

			response = outWriter.toString();

		} catch (Exception e) {
			response = e.getMessage();
			e.printStackTrace();
		} finally {
			SDDataSource.closeConnection("getXForm", sd);
		}

		return response;

	}

	/*
	 * Create the root element
	 * 
	 * @param outputDoc
	 */
	public Element populateRoot() {

		Element rootElement = outputDoc.createElement("root");
		/*
		 * rootElement.setAttribute("xmlns:ev", "http://www.w3.org/2001/xml-events");
		 * rootElement.setAttribute("xmlns:h", "http://www.w3.org/1999/xhtml");
		 * rootElement.setAttribute("xmlns:jr", "http://openrosa.org/javarosa");
		 * rootElement.setAttribute("xmlns:xsd", "http://www.w3.org/2001/XMLSchema");
		 * rootElement.setAttribute("xmlns:xf", "http://www.w3.org/2002/xforms");
		 * rootElement.setAttribute("xmlns:xalan", "http://xml.apache.org/xalan\"");
		 */
		outputDoc.appendChild(rootElement);

		return rootElement;
	}

	public void createForm(Element parent, boolean isWebForms, boolean useNodesets)
			throws Exception {

		Element bodyElement = outputDoc.createElement("form");
		bodyElement.setAttribute("novalidate", "novalidate");
		bodyElement.setAttribute("autocomplete", "off");
		bodyElement.setAttribute("class",
				"or clearfix" + (survey.surveyClass != null ? (" " + survey.surveyClass) : ""));
		bodyElement.setAttribute("dir", "ltr");
		bodyElement.setAttribute("id", survey.getIdent());

		populateForm(bodyElement);
		parent.appendChild(bodyElement);
	}

	private void populateForm(Element parent) throws Exception {

		// logo
		Element bodyElement = outputDoc.createElement("section");
		bodyElement.setAttribute("class", "form-logo");
		bodyElement.setTextContent(" "); // Set a dummy value a enketo does not understand empty sections
		parent.appendChild(bodyElement);

		// title
		bodyElement = outputDoc.createElement("h3");
		bodyElement.setAttribute("id", "form-title");
		bodyElement.setAttribute("dir", "auto");
		bodyElement.setTextContent(survey.getDisplayName());
		parent.appendChild(bodyElement);

		// Languages
		bodyElement = outputDoc.createElement("select");
		bodyElement.setAttribute("id", "form-languages");
		if (survey.languages == null || survey.languages.size() == 0) {
			bodyElement.setAttribute("style", "display:none;");
		}
		bodyElement.setAttribute("data-default-lang", survey.def_lang);
		populateLanguageChoices(bodyElement);
		parent.appendChild(bodyElement);

		// Questions
		for (Form form : survey.forms) {
			if (form.parentform == 0) { // Start with top level form
				addPaths(form, "/");
				processQuestions(parent, form);
				processPreloads(parent, form);
				processCalculations(parent, form);
				break;
			}
		}

	}

	private void populateLanguageChoices(Element parent) {
		Element bodyElement = null;
		int idx = 0;
		for (Language lang : survey.languages) {
			bodyElement = outputDoc.createElement("option");
			bodyElement.setAttribute("value", lang.name);
			bodyElement.setTextContent(lang.name);
			parent.appendChild(bodyElement);

			// Save the index of the default language
			if (lang.name.equals(survey.def_lang)) {
				languageIndex = idx;
			}
			idx++;
		}
	}

	/*
	 * Associate question names with their paths
	 */
	private void addPaths(Form form, String pathStem) {

		Stack<String> pathStack = new Stack<>(); // Store the paths as we go in and out of groups

		pathStem = pathStem + form.name + "/";

		for (Question q : form.questions) {

			paths.put(getRefName(q.name, form), pathStem + q.name); // Save the path

			if (q.type.equals("end group")) {

				pathStem = pathStack.pop();

			} else if (q.type.equals("begin group")) {

				pathStack.push(pathStem);
				pathStem = pathStem + q.name + "/";

			} else if (q.type.equals("begin repeat")) {

				for (Form subForm : survey.forms) {
					if (subForm.parentQuestion == q.id) { // continue with next form
						addPaths(subForm, pathStem);
						break;
					}
				}

			}
		}

	}

	/*
	 * Process the main block of questions Skip over: - preloads - meta group
	 */
	private void processQuestions(Element parent, Form form) throws Exception {

		Element bodyElement = null;
		Element currentParent = parent;
		Stack<Element> elementStack = new Stack<>(); // Store the elements for non repeat groups

		for (Question q : form.questions) {

			if (!q.inMeta && !q.name.equals("meta_groupEnd") && !q.isPreload() && !q.type.equals("calculate")) {
				if (q.type.equals("end group")) {

					currentParent = elementStack.pop();

				} else if (q.type.equals("begin group")) {

					elementStack.push(currentParent);
					currentParent = addGroupWrapper(currentParent, q, false, form);

				} else if (q.type.equals("begin repeat")) {

					elementStack.push(currentParent);
					currentParent = addGroupWrapper(currentParent, q, true, form);

					addRepeat(currentParent, q, form);

					// repeat into
					Element repeatInfo = outputDoc.createElement("div");
					repeatInfo.setAttribute("class", "or-repeat-info");
					repeatInfo.setAttribute("data-name", paths.get(getRefName(q.name, form)));
					if (q.calculation != null && q.calculation.trim().length() > 0) {
						repeatInfo.setAttribute("data-repeat-count", paths.get(getRefName(q.name, form)) + "_count");
						
					}
					currentParent.appendChild(repeatInfo);

					// Exit the group
					currentParent = elementStack.pop();

				} else if (q.isSelect()) {

					/*
					 * Create fieldSet or Label depending on the attributes
					 */
					if(minSelect(q.appearance)) {
						bodyElement = outputDoc.createElement("label");
						setQuestionClass(q, bodyElement);
						
						addMinimalSelectContents(bodyElement, q, form);
						currentParent.appendChild(bodyElement);
						
					} else {
						bodyElement = outputDoc.createElement("fieldset");
						setQuestionClass(q, bodyElement);
	
						Element extraFieldsetElement = outputDoc.createElement("fieldset");
						bodyElement.appendChild(extraFieldsetElement);
	
						addSelectContents(extraFieldsetElement, q, form);
						currentParent.appendChild(bodyElement);
					}

				} else {

					// Non select question
					bodyElement = outputDoc.createElement("label");
					setQuestionClass(q, bodyElement);

					addLabelContents(bodyElement, q, form);
					currentParent.appendChild(bodyElement);

				}
			}
		}

	}

	/*
	 * Question classes
	 */
	private void setQuestionClass(Question q, Element elem) {

		StringBuffer classVal = new StringBuffer("");

		if (q.type.equals("note") || (q.type.equals("text") && q.readonly)) {
			classVal.append("note");
		} else if (q.type.equals("begin group") || q.type.equals("begin repeat")) {
			if (hasLabel(q)) {
				classVal.append("or-group");
			} else {
				classVal.append("or-group-data");
			}
		} else {
			classVal.append("question");
			if (!q.isSelect()) {
				classVal.append(" non-select");
			} else if (!q.appearance.contains("likert") && !minSelect(q.appearance)) {
				classVal.append(" simple-select");
			}
		}



		// Mark the question as a branch if it has a relevance
		if (q.relevant != null && q.relevant.trim().length() > 0) {
			classVal.append(" or-branch pre-init");
		}

		// Add appearances
		String[] appList = q.appearance.split(" ");
		for (int i = 0; i < appList.length; i++) {
			if (appList[i] != null && appList[i].trim().length() > 0) {
				classVal.append(" or-appearance-");
				classVal.append(appList[i].toLowerCase().trim());
			}
		}
		elem.setAttribute("class", classVal.toString());
	}

	/*
	 * Process the main block of questions Preloads are only in the top level form
	 */
	private void processPreloads(Element parent, Form form) {

		Element preloadLabel = null;
		Element preloadInput = null;
		Element bodyElement = outputDoc.createElement("fieldset");
		bodyElement.setAttribute("style", "display:none;");
		bodyElement.setAttribute("id", "or-preload-items");

		for (Question q : form.questions) {

			if (q.isPreload() && !q.inMeta) {
				preloadLabel = outputDoc.createElement("label");
				preloadLabel.setAttribute("class", "calculation non-select");
				bodyElement.appendChild(preloadLabel);

				preloadInput = outputDoc.createElement("input");
				preloadInput.setAttribute("type", "hidden");
				preloadInput.setAttribute("name", paths.get(getRefName(q.name, form)));
				preloadInput.setAttribute("data-preload", q.source);
				preloadInput.setAttribute("data-preload-params", q.source_param);
				preloadInput.setAttribute("data-type-xml", getXmlType(q));
				preloadLabel.appendChild(preloadInput);
			}
		}
		parent.appendChild(bodyElement);

	}

	/*
	 * Process the main block of questions Preloads are only in the top level form
	 */
	private void processCalculations(Element parent, Form form) throws Exception {

		Element calculationLabel = null;
		Element calculationInput = null;
		Element bodyElement = outputDoc.createElement("fieldset");
		bodyElement.setAttribute("style", "display:none;");
		bodyElement.setAttribute("id", "or-calculated-items");

		for (Question q : form.questions) {

			if (q.calculation != null && q.calculation.trim().length() > 0) {

				calculationLabel = outputDoc.createElement("label");
				calculationLabel.setAttribute("class", "calculation non-select");
				bodyElement.appendChild(calculationLabel);

				calculationInput = outputDoc.createElement("input");
				calculationInput.setAttribute("type", "hidden");

				if (q.type.equals("begin repeat")) {
					calculationInput.setAttribute("name", paths.get(getRefName(q.name, form)) + "_count");
				} else {
					calculationInput.setAttribute("name", paths.get(getRefName(q.name, form)));
				}

				calculationInput.setAttribute("data-calculate", " " +
						UtilityMethods.convertAllxlsNames(q.calculation, false, paths, form.id, true) + " ");

				calculationInput.setAttribute("data-type-xml", "string"); // Always string for calculate
				calculationLabel.appendChild(calculationInput);
			}

		}
		parent.appendChild(bodyElement);

	}

	/*
	 * Add the contents of a select
	 *   - minimal
	 *   - autocomplete
	 *   - search
	 */
	private void addMinimalSelectContents(Element parent, Question q, Form form) throws Exception {

		// Add select
		Element selectElement = outputDoc.createElement("select");
		parent.appendChild(selectElement);
		selectElement.setAttribute("name", paths.get(getRefName(q.name, form)));
		selectElement.setAttribute("data-name", paths.get(getRefName(q.name, form)));
		selectElement.setAttribute("data-type-xml", q.type);
		selectElement.setAttribute("style", "display:none;");
		if(q.type.equals("select")) {
			selectElement.setAttribute("multiple", "multiple");
		}

		// Add template option
		Element templateElement = outputDoc.createElement("option");
		selectElement.appendChild(templateElement);
		templateElement.setAttribute("class", "itemset-template");
		templateElement.setAttribute("data-items-path", getNodeset(q, form));
		templateElement.setAttribute("data-type-xml", q.type);
		templateElement.setAttribute("style", "display:none;");
		templateElement.setAttribute("value", "");
		templateElement.setTextContent("...");
		if(q.type.equals("select")) {
			templateElement.setAttribute("multiple", "multiple");
		}
		
		// Option translations section
		// <span class="or-option-translations" style="display:none;">
		Element otElement = outputDoc.createElement("span");
		parent.appendChild(otElement);
		otElement.setAttribute("class",  "or-option-translations");
		otElement.setAttribute("style", "display:none;");
	
		// Itemset labels
		Element optionElement = outputDoc.createElement("span");
		parent.appendChild(optionElement);
		optionElement.setAttribute("class", "itemset-labels");
		optionElement.setAttribute("data-value-ref", "name");
		optionElement.setAttribute("data-label-type", "itext");
		optionElement.setAttribute("data-label-ref", "itextId");

		addOptionLabels(optionElement, q, form);

	}
	
	/*
	 * Add the contents of a minimal select
	 * 
	 */
	private void addSelectContents(Element parent, Question q, Form form) throws Exception {

		// legend
		Element bodyElement = outputDoc.createElement("legend");
		addLabels(bodyElement, q, form);
		parent.appendChild(bodyElement);

		Element optionWrapperElement = outputDoc.createElement("div");
		optionWrapperElement.setAttribute("class", "option-wrapper");

		// options
		addOptions(optionWrapperElement, q, form);
		parent.appendChild(optionWrapperElement);

	}

	/*
	 * Add the contents of a label
	 */
	private void addLabelContents(Element parent, Question q, Form form) {

		// span
		addLabels(parent, q, form);

		/*
		 * Input
		 */
		Element bodyElement = outputDoc.createElement("input");
		bodyElement.setAttribute("type", getInputType(q));
		bodyElement.setAttribute("name", paths.get(getRefName(q.name, form)));
		bodyElement.setAttribute("data-type-xml", getXmlType(q));

		// media specific
		if (q.type.equals("image")) {
			bodyElement.setAttribute("accept", "image/*");
		} else if (q.type.equals("audio")) {
			bodyElement.setAttribute("accept", "audio/*");
		} else if (q.type.equals("video")) {
			bodyElement.setAttribute("accept", "video/*");
		}

		// note and read only specific
		if (q.type.equals("note") || q.readonly) {
			bodyElement.setAttribute("readonly", "readonly");
		}

		// Required - note allow required on read only questions to support form level
		// validation trick
		if (q.required) {
			bodyElement.setAttribute("data-required", "true()");
		}

		// decimal
		if (q.type.equals("decimal")) {
			bodyElement.setAttribute("step", "any");
		}

		// constraint
		if (q.constraint != null && q.constraint.trim().length() > 0) {
			try {
				bodyElement.setAttribute("data-constraint",
						UtilityMethods.convertAllxlsNames(q.constraint, false, paths, form.id, true));
			} catch (Exception e) {
				log.log(Level.SEVERE, e.getMessage(), e);
			}
		}

		// relevant
		if (q.relevant != null && q.relevant.trim().length() > 0) {
			try {
				bodyElement.setAttribute("data-relevant",
						UtilityMethods.convertAllxlsNames(q.relevant, false, paths, form.id, true));
			} catch (Exception e) {
				log.log(Level.SEVERE, e.getMessage(), e);
			}
		}

		parent.appendChild(bodyElement);
	}

	private void addOptions(Element parent, Question q, Form form) throws Exception {

		// Itemset Template
		Element bodyElement = outputDoc.createElement("label");
		bodyElement.setAttribute("class", "itemset-template");
		
		bodyElement.setAttribute("data-items-path", getNodeset(q, form));
		parent.appendChild(bodyElement);
		
		Element inputElement = outputDoc.createElement("input");
		inputElement.setAttribute("type", getInputType(q));
		inputElement.setAttribute("name", paths.get(getRefName(q.name, form)));
		inputElement.setAttribute("data-name", paths.get(getRefName(q.name, form)));
		inputElement.setAttribute("data-type-xml", getXmlType(q));
		inputElement.setAttribute("value", "");
		bodyElement.appendChild(inputElement);

		// Itemset labels
		Element optionElement = outputDoc.createElement("span");
		optionElement.setAttribute("class", "itemset-labels");
		optionElement.setAttribute("data-value-ref", "name");
		optionElement.setAttribute("data-label-type", "itext");
		optionElement.setAttribute("data-label-ref", "itextId");

		addOptionLabels(optionElement, q, form);

		parent.appendChild(optionElement);

	}

	private void addOptionLabels(Element parent, Question q, Form form) {

		ArrayList<Option> options = survey.optionLists.get(q.list_name).options;
		for (Option o : options) {
			int idx = 0;
			Element bodyElement = null;
			for (Language lang : survey.languages) {
				bodyElement = outputDoc.createElement("span");
				bodyElement.setAttribute("lang", lang.name);
				bodyElement.setAttribute("class",
						"option-label" + (lang.name.equals(survey.def_lang) ? " active" : ""));
				bodyElement.setAttribute("data-itext-id", o.text_id);

				String label = o.labels.get(idx).text;
				try {
					label = UtilityMethods.convertAllxlsNames(o.labels.get(idx).text, true, paths, form.id, true);
				} catch (Exception e) {
					log.log(Level.SEVERE, e.getMessage(), e);
				}
				bodyElement.setTextContent(label);
				parent.appendChild(bodyElement);
				
				addMedia(parent, o.labels.get(idx), lang, o.text_id);
				
				idx++;
			}

			parent.appendChild(bodyElement);
		}

	}

	/*
	 * Add a wrapper for a group then return the new parent
	 */
	private Element addGroupWrapper(Element parent, Question q, boolean repeat, Form form) {
		Element groupElement = outputDoc.createElement("section");
		parent.appendChild(groupElement);
		setQuestionClass(q, groupElement);

		if (!repeat) {
			groupElement.setAttribute("name", paths.get(getRefName(q.name, form)));
		}
		addGroupTitle(groupElement, q, form);
		return groupElement;
	}

	/*
	 * Add a wrapper for a repeat then return the new parent
	 */
	private Form addRepeat(Element parent, Question q, Form form) throws Exception {

		Form newForm = null;

		Element bodyElement = outputDoc.createElement("section");
		bodyElement.setAttribute("class", "or-repeat");
		bodyElement.setAttribute("name", paths.get(getRefName(q.name, form)));

		// Process sub form
		for (Form subForm : survey.forms) {
			if (subForm.parentQuestion == q.id) { // continue with next form
				processQuestions(bodyElement, subForm);
				newForm = subForm;
				break;
			}
		}

		parent.appendChild(bodyElement);

		return newForm;

	}

	private void addGroupTitle(Element parent, Question q, Form form) {
		Element bodyElement = outputDoc.createElement("h4");
		addLabels(bodyElement, q, form);
		parent.appendChild(bodyElement);
	}

	private void addLabels(Element parent, Question q, Form form) {
		int idx = 0;
		Element bodyElement = null;
		for (Language lang : survey.languages) {

			// Label
			bodyElement = outputDoc.createElement("span");
			bodyElement.setAttribute("lang", lang.name);
			bodyElement.setAttribute("class", "question-label" + (lang.name.equals(survey.def_lang) ? " active" : ""));
			bodyElement.setAttribute("data-itext-id", q.text_id);

			String label = q.labels.get(idx).text;
			try {
				label = UtilityMethods.convertAllxlsNames(label, true, paths, form.id, true);
				label = convertMarkdown(label);

			} catch (Exception e) {
				log.log(Level.SEVERE, e.getMessage(), e);
			}
			bodyElement.setTextContent(label);
			parent.appendChild(bodyElement);

			// Hint
			String hint = q.labels.get(idx).hint;
			if (hint != null && hint.trim().length() > 0) {
				bodyElement = outputDoc.createElement("span");
				bodyElement.setAttribute("lang", lang.name);
				bodyElement.setAttribute("class", "or-hint" + (lang.name.equals(survey.def_lang) ? " active" : ""));
				bodyElement.setAttribute("data-itext-id", q.hint_id);

				try {
					label = UtilityMethods.convertAllxlsNames(q.labels.get(idx).hint, true, paths, form.id, true);
				} catch (Exception e) {
					log.log(Level.SEVERE, e.getMessage(), e);
				}
				bodyElement.setTextContent(hint);
				parent.appendChild(bodyElement);
			}

			addMedia(parent, q.labels.get(idx), lang, q.text_id);


			idx++;
		}

		// Constraint message
		if (q.constraint_msg != null && q.constraint_msg.length() > 0) {
			bodyElement = outputDoc.createElement("span");
			bodyElement.setAttribute("lang", "");
			bodyElement.setAttribute("class", "or-constraint-msg active");
			bodyElement.setTextContent(q.constraint_msg);
			parent.appendChild(bodyElement);
		}

		// Required
		if (q.required) {
			bodyElement = outputDoc.createElement("span");
			bodyElement.setAttribute("class", "required");
			bodyElement.setTextContent("*");
			parent.appendChild(bodyElement);

			// Message
			bodyElement = outputDoc.createElement("span");
			bodyElement.setAttribute("class", "or-required-msg active");
			bodyElement.setAttribute("data-i18n", "constraint.required");
			if (q.required_msg != null && q.required_msg.trim().length() > 0) {
				bodyElement.setTextContent(q.required_msg);
			} else {
				bodyElement.setTextContent("This field is required");
			}
			parent.appendChild(bodyElement);
		}
	}

	/*
	 * Add media
	 */
	private void addMedia(Element parent, Label label, Language lang, String textId) {
		
		Element bodyElement = null;
		
		// Image
		String image = label.image;
		if (image != null && image.trim().length() > 0) {
			bodyElement = outputDoc.createElement("img");
			bodyElement.setAttribute("lang", lang.name);
			bodyElement.setAttribute("class", (lang.name.equals(survey.def_lang) ? " active" : ""));
			bodyElement.setAttribute("src", "jr://images/" + image);
			bodyElement.setAttribute("alt", "image");
			bodyElement.setAttribute("data-itext-id", textId);

			parent.appendChild(bodyElement);
		}

		// Audio
		String audio = label.audio;
		if (audio != null && image.trim().length() > 0) {
			bodyElement = outputDoc.createElement("audio");
			bodyElement.setAttribute("lang", lang.name);
			bodyElement.setAttribute("class", (lang.name.equals(survey.def_lang) ? " active" : ""));
			bodyElement.setAttribute("src", "jr://audio/" + audio);
			bodyElement.setAttribute("alt", "audio");
			bodyElement.setAttribute("data-itext-id", textId);

			parent.appendChild(bodyElement);
		}

		// Video
		String video = label.video;
		if (audio != null && image.trim().length() > 0) {
			bodyElement = outputDoc.createElement("video");
			bodyElement.setAttribute("lang", lang.name);
			bodyElement.setAttribute("class", (lang.name.equals(survey.def_lang) ? " active" : ""));
			bodyElement.setAttribute("src", "jr://video/" + video);
			bodyElement.setAttribute("alt", "video");
			bodyElement.setAttribute("data-itext-id", textId);

			parent.appendChild(bodyElement);
		}
	}
	/*
	 * Return the input type required by enketo
	 */
	private String getInputType(Question q) {

		String type = null;
		if (q.type.equals("int")) {
			type = "number";
		} else if (q.type.equals("string")) {
			type = "text";
		} else if (q.type.equals("select1")) {
			type = "radio";
		} else if (q.type.equals("select")) {
			type = "checkbox";
		} else if (q.type.equals("geopoint") || q.type.equals("geoshape") || q.type.equals("geotrace")) {
			type = "text";
		} else if (q.type.equals("image") || q.type.equals("audio") || q.type.equals("video")) {
			type = "file";
		} else if (q.type.equals("date")) {
			type = "date";
		} else if (q.type.equals("dateTime")) {
			type = "datetime";
		} else if (q.type.equals("time")) {
			type = "time";
		} else if (q.type.equals("note")) {
			type = "text";
		} else if (q.type.equals("decimal")) {
			type = "number";
		} else {
			log.info("#### unknown type: " + q.type + " for question " + q.name);
			type = "text";
		}
		return type;
	}

	/*
	 * Return the input type required by enketo
	 */
	private String getXmlType(Question q) {

		String type = null;
		if (q.type.equals("calculate")) {
			type = "string";
		} else if (q.type.equals("image") || q.type.equals("audio") || q.type.equals("video")) {
			type = "binary";
		} else if (q.type.equals("note")) {
			type = "string";
		} else {
			type = q.type;
		}
		return type;
	}

	/*
	 * Returns true if the question has any label - text, image, audio or video
	 */
	private boolean hasLabel(Question q) {
		boolean hasLabel = false;

		for (int i = 0; i < survey.languages.size(); i++) {
			if (q.labels.get(i) != null) {
				Label l = q.labels.get(i);
				if ((l.text != null && l.text.trim().length() > 0) || l.image != null || l.video != null
						|| l.audio != null) {
					hasLabel = true;
					break;
				}
			}

		}
		return hasLabel;

	}

	private String getRefName(String qName, Form form) {
		if (qName.equals("_the_geom")) {
			return form.id + "_the_geom";
		} else {
			return qName;
		}
	}

	private String convertMarkdown(String in) {

		// Test for links
		StringBuffer out = new StringBuffer();
		String pattern = "\\[([^]]*)\\]\\(([^\\s^\\)]*)[\\s\\)]"; // from https://stackoverflow.com/a/40178293/1867651
		Pattern r = Pattern.compile(pattern);
		Matcher m = r.matcher(in);

		int start = 0;
		while (m.find()) {
			if (m.start() > start) {
				out.append(in.substring(start, m.start()));
			}
			out.append("<a href =\"");
			out.append(m.group(2));
			out.append("\" target=\"_blank\">");
			out.append(m.group(1));
			out.append("</a>");

			start = m.end() + 1;
		}

		if (start < in.length()) {
			out.append(in.substring(start));
		}

		return out.toString();
	}

	/*
	 * Convert binary hex to Unicode
	 */
	private String unescapeEmoji(String input) {
		StringBuffer output = new StringBuffer("");
		String replaced;

		Pattern pattern = Pattern.compile("&#[0-9A-Fa-f]*?;");
		java.util.regex.Matcher matcher = pattern.matcher(input);
		int start = 0;
		while (matcher.find()) {

			String matched = matcher.group();
			replaced = matched.replaceAll("&#", "");
			replaced = replaced.replaceAll(";", " ");

			// Add any text before the match
			int startOfGroup = matcher.start();
			String initial = input.substring(start, startOfGroup).trim();

			output.append(initial);
			output.append(replaced);

			// Reset the start
			start = matcher.end();

		}

		// Get the remainder of the string
		if (start < input.length()) {
			replaced = input.substring(start).trim();
			output.append(replaced);
		}

		return output.toString();
	}
	
	private boolean minSelect(String appearance) {
		
		if(appearance.contains("minimal") || appearance.contains("autocomplete") || appearance.contains("search") ) {
			return true;
		} else {
			return false;
		}
	}

	/*
	 * Attempt to get the full nodeset incorporating any external filters
	 */
	private String getNodeset(Question q, Form form) throws Exception {
		return UtilityMethods.getNodeset(true, false, paths, true, q.nodeset, q.appearance, form.id);
	}
}