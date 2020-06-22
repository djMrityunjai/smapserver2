package utilities;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import org.javarosa.core.model.condition.IFunctionHandler;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.core.model.instance.InstanceInitializationFactory;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.model.utils.IPreloadHandler;
import org.javarosa.model.xform.XFormsModule;
import org.javarosa.xform.util.XFormUtils;
import org.smap.model.SurveyTemplate;
import org.smap.server.utilities.GetXForm;

public class JavaRosaUtilities {

    /*
     * Validate a survey stored in the database using the javarosa api
     * Will throw an exception on errors
     */
    public static void javaRosaSurveyValidation(ResourceBundle localisation, int sId, String user, String tz) throws Exception {
		
    		class FakePreloadHandler implements IPreloadHandler {

            String preloadHandled;


            public FakePreloadHandler(String preloadHandled) {
                this.preloadHandled = preloadHandled;
            }


            public boolean handlePostProcess(TreeElement arg0, String arg1) {
                // TODO Auto-generated method stub
                return false;
            }


            public IAnswerData handlePreload(String arg0) {
                // TODO Auto-generated method stub
                return null;
            }


            public String preloadHandled() {
                // TODO Auto-generated method stub
                return preloadHandled;
            }

        }
		new XFormsModule().registerModule();
		
		SurveyTemplate template = new SurveyTemplate(localisation);
		template.readDatabase(sId, false);
		GetXForm xForm = new GetXForm(localisation, user, tz);

		String xmlForm = xForm.get(template, false, true, false, user);
		/*
		 * Replace Smap custom functions with methods that will pass in java rosa
		 * lookup  --> pulldata
		 * lookup_choices  --> search
		 * lookup_image_labels(...)  --> dummy fn round(1.1)
		 * get_media(....)  --> dummy fn round(1.1)
		 * 
		 * TODO validate these functions
		 * Also this does not allow for nested functions, however if a match
		 * does not happen here then the error will be caught and discarded.  This might
		 * mean that other errors in the form are also discarded.
		 * Probably this call to java rosa validation should be replaced by our own validation
		 */
		xmlForm = xmlForm.replace("lookup(", "pulldata(");  // lookup
		xmlForm = xmlForm.replace("lookup_choices(", "search(");  // lookup_choices
		xmlForm = xmlForm.replaceAll("lookup_image_labels\\([a-zA-Z0-9$,\\.{}\'/ ]*\\)", "round(1.1)");	// lookup_imag_labels
		xmlForm = xmlForm.replaceAll("get_media\\([a-zA-Z0-9$,\\.{}\'/ ]*\\)", "round(1.1)");	// lookup_imag_labels
		
		// Remove any actions
		xmlForm = xmlForm.replaceAll("\\<odk:setgeopoint [a-zA-Z0-9$,\\\\.{}=\\'\\-\"/ ]*\\/\\>", "");	
				
		InputStream is = new ByteArrayInputStream(xmlForm.getBytes());

		org.javarosa.core.model.FormDef fd = XFormUtils.getFormFromInputStream(is);

		// make sure properties get loaded
		fd.getPreloader().addPreloadHandler(new FakePreloadHandler("property"));

		// update evaluation context for function handlers
		fd.getEvaluationContext().addFunctionHandler(new IFunctionHandler() {

			public String getName() {
				return "pulldata";
			}

			public List<Class[]> getPrototypes() {
				return new ArrayList<Class[]>();
			}

			public boolean rawArgs() {
				return true;
			}

			public boolean realTime() {
				return false;
			}

			@Override
			public Object eval(Object[] arg0, org.javarosa.core.model.condition.EvaluationContext arg1) {
				// TODO Auto-generated method stub
				return arg0[0];
			}
		});

		fd.initialize(false, new InstanceInitializationFactory());

	}
}
