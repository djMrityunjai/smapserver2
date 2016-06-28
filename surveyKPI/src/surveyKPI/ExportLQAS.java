package surveyKPI;

/*
This file is part of SMAP.

SMAP is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

SMAP is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with SMAP.  If not, see <http://www.gnu.org/licenses/>.

*/

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.smap.sdal.Utilities.Authorise;
import org.smap.sdal.Utilities.GeneralUtilityMethods;
import org.smap.sdal.Utilities.ResultsDataSource;
import org.smap.sdal.Utilities.SDDataSource;
import org.smap.sdal.managers.LogManager;
import org.smap.sdal.managers.PDFManager;
import org.smap.sdal.managers.SurveyManager;
import org.smap.sdal.model.LQAS;
import org.smap.sdal.model.LQASGroup;
import org.smap.sdal.model.LQASItem;
import org.smap.sdal.model.LQASdataItem;

import utilities.XLSFormManager;
import utilities.XLS_LQAS_Manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.itextpdf.tool.xml.ElementList;
import com.itextpdf.tool.xml.parser.XMLParser;

import net.sourceforge.jeval.Evaluator;

/*
 * Creates an LQAS report in XLS
 */

@Path("/lqasExport/{sId}")
public class ExportLQAS extends Application {
	
	Authorise a = new Authorise(null, Authorise.ANALYST);
	
	private static Logger log =
			 Logger.getLogger(ExportLQAS.class.getName());

	LogManager lm = new LogManager();		// Application log
	
	/*
	 * Assume:
	 *  1) LQAS surveys only have one form and this form is the one that has the "lot" question in it
	 */

	
	@GET
	@Produces("application/x-download")
	public Response getXLSFormService (@Context HttpServletRequest request, 
			@Context HttpServletResponse response,
			@PathParam("sId") int sId,
			@QueryParam("sources") boolean showSources,
			@QueryParam("filetype") String filetype) throws Exception {

		try {
		    Class.forName("org.postgresql.Driver");	 
		} catch (ClassNotFoundException e) {
			log.log(Level.SEVERE, "Can't find PostgreSQL JDBC Driver", e);
		    throw new Exception("Can't find PostgreSQL JDBC Driver");
		}
				
		// Authorisation - Access
		Connection sd = SDDataSource.getConnection("createLQAS");	
		a.isAuthorised(sd, request.getRemoteUser());		
		a.isValidSurvey(sd, request.getRemoteUser(), sId, false);
		// End Authorisation 
		
		lm.writeLog(sd, sId, request.getRemoteUser(), "view", "Export to LQAS");
		
		SurveyManager sm = new SurveyManager();
		org.smap.sdal.model.Survey survey = null;
		Connection cResults = ResultsDataSource.getConnection("createLQAS");
		
		String basePath = GeneralUtilityMethods.getBasePath(request);
		
		// Set file type to "xlsx" unless "xls" has been specified
		if(filetype == null || !filetype.equals("xls")) {
			filetype = "xlsx";
		}
		
		try {
			
			// Get the survey details
			survey = sm.getById(sd, cResults, request.getRemoteUser(), sId, false, basePath, null, false, false, false, false, "real");
			
			/*
			 * Get the LQAS definition to apply to this survey
			 */
			//LQAS lqas = getLqasConfig();
			LQAS lqas = new LQAS("sa");
			
			// Add data items
			lqas.dataItems.add(new LQASdataItem("head_gender", "head_gender", null, true));
			lqas.dataItems.add(new LQASdataItem("head_age",  "head_age", null, true));
			lqas.dataItems.add(new LQASdataItem("caregiver_gender","caregiver_gender", null, true));
			lqas.dataItems.add(new LQASdataItem("caregiver_age","caregiver_age", null, true));
			lqas.dataItems.add(new LQASdataItem("child_gender","child_gender", null, true));
			lqas.dataItems.add(new LQASdataItem("breastfeed","breastfeed", null, true));
			lqas.dataItems.add(new LQASdataItem("still_bf","still_bf", null, true));
			lqas.dataItems.add(new LQASdataItem("vita_source", 
					"cereal = '1' or leafy = '1' or vita_fruits = '1' or organ = '1' or flesh = '1' or egg = '1' or fish = '1'", 
					new String[] {"cereal", "leafy", "vita_fruits", "organ", "flesh", "egg", "fish"}, true));
			lqas.dataItems.add(new LQASdataItem("iron_source", 
					"organ = '1' or flesh = '1' or egg = '1' or fish = '1' or insect = '1'", 
					new String[] {"organ", "flesh", "egg", "fish", "insect"}, true));
			lqas.dataItems.add(new LQASdataItem("animal_source",  
					"organ = '1' or flesh = '1' or egg = '1' or fish = '1' or dairy = '1' or insect = '1'", 
					new String[] {"organ", "flesh", "egg", "fish", "dairy", "insect"}, true));
			
			lqas.dataItems.add(new LQASdataItem("dairy_fg", 
					"case when dairy = '1' then 1 else 0 end", 
					new String[] {"dairy"}, false));
			lqas.dataItems.add(new LQASdataItem("grains_fg", 
					"case when cereal = '1' or tubers = '1' then 1 else 0 end", 
					new String[] {"cereal", "tubers"}, false));
			lqas.dataItems.add(new LQASdataItem("vita_fg", 
					"case when vita = '1' or leafy = '1' or vita_fruits = '1' or insect = '1' then 1 else 0 end", 
					new String[] {"vita", "leafy", "vita_fruits", "insect"}, false));
			lqas.dataItems.add(new LQASdataItem("fruits_fg", 
					"case when fruits = '1' then 1 else 0 end", 
					new String[] {"fruits"}, false));
			lqas.dataItems.add(new LQASdataItem("eggs_fg", 
					"case when egg = '1' then 1 else 0 end",
					new String[] {"egg"}, false));
			lqas.dataItems.add(new LQASdataItem("meat_fg", 
					"case when organ = '1' or flesh='1' or fish = '1' or solid='1' then 1 else 0 end", 
					new String[] {"organ", "flesh", "fish", "solid"}, false));
			lqas.dataItems.add(new LQASdataItem("nuts_fg", 
					"case when nuts = '1' then 1 else 0 end",
					new String[] {"nuts"}, false));
			lqas.dataItems.add(new LQASdataItem("oil_fg",  
					"case when oil = '1' then 1 else 0 end", 
					new String[] {"oil"}, false));
			
			lqas.dataItems.add(new LQASdataItem("eat_times",  
					"eat_times", null, false));
			
			lqas.dataItems.add(new LQASdataItem("pregnant", "pregnant", null, true));
			lqas.dataItems.add(new LQASdataItem("pregnant_avoid", "pregnant_avoid", null, true));
			
			

			
			// Basic information group
			LQASGroup g = new LQASGroup("Basic Information");
			g.items.add(new LQASItem("1.a1", "Gender of Head of Household", "#{head_gender} == 'F'", "Female", new String[] {"head_gender"}));
			g.items.add(new LQASItem("1.a2", "Age of Head of Household", "#{head_age}", "#", new String[] {"head_age"}));
			g.items.add(new LQASItem("1.b1", "Gender of person answering questions", "#{caregiver_gender} == 'F'", "Female", new String[] {"caregiver_gender"}));
			g.items.add(new LQASItem("1.b2", "Age of person answering questions", "#{caregiver_age}", "#", new String[] {"caregiver_age"}));
			g.items.add(new LQASItem("2", "Is the child a boy or a girl", "#{caregiver_gender} == 'F'", "Female",new String[] {"child_gender"}));
			lqas.groups.add(g);
			
			// Feeding group
			g = new LQASGroup("Infant and young child feeding");
			g.items.add(new LQASItem("4", "Has child ever been breastfed", "#{breastfeed} == '1'", "Yes", new String[] {"breastfeed"}));
			g.items.add(new LQASItem("5", "Are you still breast feeding", "#{still_bf} == '1'", "Yes", new String[] {"still_bf"}));			
			g.items.add(new LQASItem("6a", "Child ate vitamin A-rich foods in the past 24 hours", "#{vita_source} == 't'", "Q12 B, D, E, G, H, I, J",
					new String[] {"vita_source"}));
			g.items.add(new LQASItem("6b", "Child ate iron-rich foods in the past 24 hours", "#{iron_source} == 't'", "Q12 G, H, I, J or P",
					new String[] {"iron_source"}));
			g.items.add(new LQASItem("6c", "Child ate animal source foods in the past 24 hours", "#{animal_source} == 't'", "Q12 G, H, I, J, L or P",
					new String[] {"animal_source"}));
			
			g.items.add(new LQASItem("6d", "Child had food from at least 4 food groups during the previous day and night", 
					"#{dairy_fg} + #{grains_fg} + #{vita_fg} + #{fruits_fg} + #{eggs_fg} + #{meat_fg} + #{nuts_fg} + #{oil_fg} >= 4", "",
					new String[] {"dairy_fg", "grains_fg", "vita_fg", "fruits_fg", "eggs_fg", "meat_fg", "nuts_fg", "oil_fg"}));
			
			g.items.add(new LQASItem("7", "How many times did (child’s name) eat solid, semi-solid or soft foods other than liquids yesterday during the day or at night?", 
					"#{eat_times} >= 3", "",
					new String[] {"eat_times"}));
			
			g.items.add(new LQASItem("7a", "Child is breastfed AND ate solid or semi-solid foods at least three times in the past 24 hours", 
					"#{eat_times} >= 3 && #{still_bf} == '1'", "",
					new String[] {"eat_times", "still_bf"}));
			
			g.items.add(new LQASItem("7b", "Child is breastfed AND received at least four food groups  in the past 24 hours", 
					"#{dairy_fg} + #{grains_fg} + #{vita_fg} + #{fruits_fg} + #{eggs_fg} + #{meat_fg} + #{nuts_fg} + #{oil_fg} >= 4 && #{still_bf} == '1'", "",
					new String[] {"dairy_fg", "grains_fg", "vita_fg", "fruits_fg", "eggs_fg", "meat_fg", "nuts_fg", "oil_fg", "still_bf"}));
			
			g.items.add(new LQASItem("7c", "Child is breastfed, ate solid/semi-solid foods at least 3 times AND received minimum dietary diversity (4 food groups) in past 24 hours", 
					"#{dairy_fg} + #{grains_fg} + #{vita_fg} + #{fruits_fg} + #{eggs_fg} + #{meat_fg} + #{nuts_fg} + #{oil_fg} >= 4 && #{still_bf} == '1' && #{eat_times} >= 3", "",
					new String[] {"dairy_fg", "grains_fg", "vita_fg", "fruits_fg", "eggs_fg", "meat_fg", "nuts_fg", "oil_fg", "still_bf", "eat_times"}));
			
			g.items.add(new LQASItem("7d", "Child is not breastfed AND ate solid or semi-solid foods at least 4 times in 24 hours preceding survey", 
					" #{still_bf} != '1' && #{eat_times} >= 4", "",
					new String[] {"still_bf", "eat_times"}));
			
			lqas.groups.add(g);

			g = new LQASGroup("Family planning");
			g.items.add(new LQASItem("8", "Are you currently pregnant", "#{pregnant} == '1'", "Yes", new String[] {"pregnant"}));
			g.items.add(new LQASItem("9", "Are you currently doing something to delay or avoid getting pregnant", "#{pregnant_avoid} == '1'", "Yes", new String[] {"pregnant_avoid"}));
			
			lqas.groups.add(g);
			
			/*
			 * End of setting up of test definition
			 */
			
			// Write out the definition ==== Temp
		   	Gson gson=  new GsonBuilder().disableHtmlEscaping().setDateFormat("yyyy-MM-dd").create();
			String json = gson.toJson(lqas);
			System.out.println("json: " + json);
			
			
			// Set file name
			GeneralUtilityMethods.setFilenameInResponse(survey.displayName + "." + filetype, response);
			
			// Create XLSForm
			XLS_LQAS_Manager xf = new XLS_LQAS_Manager(filetype);
			xf.createLQASForm(sd, cResults, response.getOutputStream(), survey, lqas, showSources);
			
		}  catch (Exception e) {
			log.log(Level.SEVERE, "Exception", e);
			throw new Exception("Exception: " + e.getMessage());
		} finally {
			
			SDDataSource.closeConnection("createLQAS", sd);		
			ResultsDataSource.closeConnection("createLQAS", cResults);
			
		}
		return Response.ok("").build();
	}
	
	private LQAS getLqasConfig() {
		
		LQAS lqas = null;
		return lqas;
	}

}
