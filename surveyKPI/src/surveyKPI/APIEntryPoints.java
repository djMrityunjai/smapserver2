package surveyKPI;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;

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

import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.smap.sdal.Utilities.ApplicationException;
import org.smap.sdal.Utilities.Authorise;
import org.smap.sdal.Utilities.GeneralUtilityMethods;
import org.smap.sdal.Utilities.SDDataSource;
import org.smap.sdal.Utilities.SystemException;
import org.smap.sdal.managers.ContactManager;
import org.smap.sdal.managers.DataManager;
import org.smap.sdal.managers.MailoutManager;
import org.smap.sdal.model.Mailout;
import org.smap.sdal.model.MailoutPerson;
import org.smap.sdal.model.MailoutPersonDt;
import org.smap.sdal.model.MailoutPersonTotals;
import org.smap.sdal.model.SubItemDt;
import org.smap.sdal.model.SubsDt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.sql.Connection;
import java.sql.Date;
import java.util.ArrayList;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/*
 * Allow the GUI to get data from API functions while having a different entry point that can 
 * be authenticated differently from the API users
 */
@Path("/api")
public class APIEntryPoints extends Application {

	private static Logger log =
			 Logger.getLogger(APIEntryPoints.class.getName());
	
	Authorise aMailout = null;
	Authorise aContacts = null;
	
	public APIEntryPoints() {
		ArrayList<String> authMailout = new ArrayList<String> ();	
		authMailout.add(Authorise.ADMIN);
		authMailout.add(Authorise.ANALYST);
		aMailout = new Authorise(authMailout, null);
		
		ArrayList<String> authContacts = new ArrayList<String> ();	
		authContacts.add(Authorise.ADMIN);
		aContacts = new Authorise(authContacts, null);
		
	}
	
	/*
	 * Get records for an individual survey in JSON format
	 */
	@GET
	@Produces("application/json")
	@Path("/data/{sIdent}")
	public Response getDataRecordsServiceSmap(@Context HttpServletRequest request,
			@Context HttpServletResponse response,
			@PathParam("sIdent") String sIdent,
			@QueryParam("start") int start,				// Primary key to start from
			@QueryParam("limit") int limit,				// Number of records to return
			@QueryParam("mgmt") boolean mgmt,
			@QueryParam("oversightSurvey") String oversightSurvey,	// Console
			@PathParam("view") int viewId,					// Console
			@QueryParam("schema") boolean schema,			// Console return schema with the data
			@QueryParam("group") boolean group,			// If set include a dummy group value in the response, used by duplicate query
			@QueryParam("sort") String sort,				// Column Human Name to sort on
			@QueryParam("dirn") String dirn,				// Sort direction, asc || desc
			@QueryParam("form") String formName,			// Form name (optional only specify for a child form)
			@QueryParam("start_parkey") int start_parkey,// Parent key to start from
			@QueryParam("parkey") int parkey,			// Parent key (optional, use to get records that correspond to a single parent record)
			@QueryParam("hrk") String hrk,				// Unique key (optional, use to restrict records to a specific hrk)
			@QueryParam("key") String key,				// Unique key (optional, use to restrict records to a specific key - same as hrk)
			@QueryParam("format") String format,			// dt for datatables otherwise assume kobo
			@QueryParam("bad") String include_bad,		// yes | only | none Include records marked as bad
			@QueryParam("completed") String include_completed,		// If yes return unassigned records that have the final status
			@QueryParam("audit") String audit_set,		// if yes return audit data
			@QueryParam("merge_select_multiple") String merge, 	// If set to yes then do not put choices from select multiple questions in separate objects
			@QueryParam("tz") String tz,					// Timezone
			@QueryParam("geojson") String geojson,		// if set to yes then format as geoJson
			@QueryParam("geom_question") String geomQuestion,
			@QueryParam("links") String links,
			@QueryParam("meta") String meta,
			@QueryParam("filter") String filter,
			@QueryParam("dd_filter") String dd_filter,		// Drill Down Filter when driling down to a child survey
			@QueryParam("prikey") int prikey,				// Return data for a specific primary key (Distinct from using start with limit 1 as this is for drill down and settings should not be stored)
			@QueryParam("dd_hrk") String dd_hrk,				// Return data matching key when drilling down to parent
			@QueryParam("dateName") String dateName,			// Name of question containing the date to filter by
			@QueryParam("startDate") Date startDate,
			@QueryParam("endDate") Date endDate,
			@QueryParam("instanceid") String instanceId,
			@QueryParam("getSettings") boolean getSettings			// if set true get the settings from the database
			) throws ApplicationException, Exception { 
			
		boolean incLinks = false;
		if(links != null && (links.equals("true") || links.equals("yes"))) {
			incLinks = true;
		}
		if(formName != null) {
			incLinks = false;		// Links can only be specified for the main form
		}
		
		if(key != null) {
			hrk = key;
		}
		
		boolean includeMeta = true;		// Default to true for get all records (Historical consistency reason)
		if(meta != null && (meta.equals("false") || meta.equals("no"))) {
			includeMeta = false;
		}
		
		// Authorisation, localisation and timezone are determined in getDataRecords
		DataManager dm = new DataManager(null, "UTC");	
		dm.getDataRecords(request, response, sIdent, start, limit, mgmt, oversightSurvey, viewId, 
				schema, group, sort, dirn, formName, start_parkey,
				parkey, hrk, format, include_bad, include_completed, audit_set, merge, geojson, geomQuestion,
				tz, incLinks, 
				filter, dd_filter, prikey, dd_hrk, dateName, startDate, endDate, getSettings, 
				instanceId, includeMeta);
		
		return Response.status(Status.OK).build();
	}
	
	/*
	 * Get a list of emails in a mailout
	 */
	@GET
	@Produces("application/json")
	@Path("/mailout/{mailoutId}/emails")
	public Response getSubscriptions(@Context HttpServletRequest request,
			@PathParam("mailoutId") int mailoutId,
			@QueryParam("dt") boolean dt
			) { 
		
		String connectionString = "API - get emails in mailout";
		Response response = null;
		ArrayList<MailoutPerson> data = new ArrayList<> ();
		
		// Authorisation - Access
		Connection sd = SDDataSource.getConnection(connectionString);
		aMailout.isAuthorised(sd, request.getRemoteUser());
		aMailout.isValidMailout(sd, request.getRemoteUser(), mailoutId);
		// End authorisation
		
		try {
	
			// Get the users locale
			Locale locale = new Locale(GeneralUtilityMethods.getUserLanguage(sd, request, request.getRemoteUser()));
			ResourceBundle localisation = ResourceBundle.getBundle("org.smap.sdal.resources.SmapResources", locale);		
			
			int oId = GeneralUtilityMethods.getOrganisationId(sd, request.getRemoteUser());
			MailoutManager mm = new MailoutManager(localisation);
			data = mm.getMailoutPeople(sd, mailoutId, oId, dt);				
			
			Gson gson=  new GsonBuilder().disableHtmlEscaping().setDateFormat("yyyy-MM-dd HH:mm:ss").create();
			
			if(dt) {
				MailoutPersonDt mpDt = new MailoutPersonDt();
				mpDt.data = data;
				response = Response.ok(gson.toJson(mpDt)).build();
			} else {
				response = Response.ok(gson.toJson(data)).build();
			}
			
	
		} catch (Exception e) {
			log.log(Level.SEVERE,"Error: ", e);
			String msg = e.getMessage();
			if(msg == null) {
				msg = "System Error";
			}
		    throw new SystemException(msg);
		} finally {
					
			SDDataSource.closeConnection(connectionString, sd);
		}
		
		return response;
		
	}
	
	/*
	 * Get a list of mailouts
	 */
	@GET
	@Path("/mailout/{survey}")
	@Produces("application/json")
	public Response getMailouts(@Context HttpServletRequest request,
			@PathParam("survey") String surveyIdent,
			@QueryParam("links") boolean links
			) { 

		Response response = null;
		String connectionString = "surveyKPI-Mailout List";
		
		// Authorisation - Access
		Connection sd = SDDataSource.getConnection(connectionString);
		aMailout.isAuthorised(sd, request.getRemoteUser());
		aMailout.isValidSurveyIdent(sd, request.getRemoteUser(), surveyIdent, false, true);
		// End Authorisation
		
		try {
			// Localisation			
			Locale locale = new Locale(GeneralUtilityMethods.getUserLanguage(sd, request, request.getRemoteUser()));
			ResourceBundle localisation = ResourceBundle.getBundle("org.smap.sdal.resources.SmapResources", locale);
						
			MailoutManager mm = new MailoutManager(localisation);
				
			String urlprefix = GeneralUtilityMethods.getUrlPrefix(request);
			ArrayList<Mailout> mailouts = mm.getMailouts(sd, surveyIdent, links, urlprefix); 
				
			Gson gson = new GsonBuilder().disableHtmlEscaping().create();
			String resp = gson.toJson(mailouts);
			response = Response.ok(resp).build();
				
		} catch (Exception e) {
			
			log.log(Level.SEVERE,"Error: ", e);
			String msg = e.getMessage();
			if(msg == null) {
				msg = "System Error";
			}
		    throw new SystemException(msg);
		    
		} finally {
			
			SDDataSource.closeConnection(connectionString, sd);
		}

		return response;
	}

	/*
	 * Add or update a mailout campaign
	 */
	@POST
	@Path("/mailout")
	public Response addUpdateMailout(@Context HttpServletRequest request,
			@FormParam("mailout") String mailoutString) { 
		
		Response response = null;
		String connectionString = "api/v1/mailout - add mailout";
		Gson gson=  new GsonBuilder().disableHtmlEscaping().setDateFormat("yyyy-MM-dd").create();
		
		Mailout mailout = null;
		try {
			mailout = gson.fromJson(mailoutString, Mailout.class);
		} catch (Exception e) {
			throw new SystemException("JSON Error: " + e.getMessage());
		}
	
		// Authorisation - Access
		Connection sd = SDDataSource.getConnection(connectionString);
		aMailout.isAuthorised(sd, request.getRemoteUser());
		if(mailout.id > 0) {
			aMailout.isValidMailout(sd, request.getRemoteUser(), mailout.id);
		}
		boolean superUser = false;
		try {
			superUser = GeneralUtilityMethods.isSuperUser(sd, request.getRemoteUser());
		} catch (Exception e) {
		}
		aMailout.isValidSurveyIdent(sd, request.getRemoteUser(), mailout.survey_ident, false, superUser);
		// End Authorisation
		
		try {	
			// Localisation			
			Locale locale = new Locale(GeneralUtilityMethods.getUserLanguage(sd, request, request.getRemoteUser()));
			ResourceBundle localisation = ResourceBundle.getBundle("org.smap.sdal.resources.SmapResources", locale);
			
			MailoutManager mm = new MailoutManager(localisation);
 
			if(mailout.id <= 0) {
				mailout.id = mm.addMailout(sd, mailout);
			} else {
				mm.updateMailout(sd, mailout);
			}
			
			response = Response.ok(gson.toJson(mailout)).build();
			
		} catch(ApplicationException e) {
			throw new SystemException(e.getMessage());
		} catch (Exception e) {
			log.log(Level.SEVERE,"Error: ", e);
			String msg = e.getMessage();
			if(msg == null) {
				msg = "System Error";
			}
		    throw new SystemException(msg);
		    
		} finally {			
			SDDataSource.closeConnection(connectionString, sd);			
		}

		return response;
	}
	
	/*
	 * Get subscription totals
	 */
	@GET
	@Produces("application/json")
	@Path("/mailout/{mailoutId}/emails/totals")
	public Response getSubscriptionTotals(@Context HttpServletRequest request,
			@PathParam("mailoutId") int mailoutId
			) { 
		
		String connectionString = "API - get emails in mailout";
		Response response = null;
		MailoutPersonTotals totals = null;
		
		// Authorisation - Access
		Connection sd = SDDataSource.getConnection(connectionString);
		aMailout.isAuthorised(sd, request.getRemoteUser());
		aMailout.isValidMailout(sd, request.getRemoteUser(), mailoutId);
		// End authorisation
		
		try {
	
			// Get the users locale
			Locale locale = new Locale(GeneralUtilityMethods.getUserLanguage(sd, request, request.getRemoteUser()));
			ResourceBundle localisation = ResourceBundle.getBundle("org.smap.sdal.resources.SmapResources", locale);		
			
			MailoutManager mm = new MailoutManager(localisation);
			totals = mm.getMailoutPeopleTotals(sd,mailoutId);		
			
			Gson gson =  new GsonBuilder().disableHtmlEscaping().setDateFormat("yyyy-MM-dd HH:mm:ss").create();
			
			response = Response.ok(gson.toJson(totals)).build();			
	
		} catch (Exception e) {
			log.log(Level.SEVERE,"Error: ", e);
			String msg = e.getMessage();
			if(msg == null) {
				msg = "System Error";
			}
		    throw new SystemException(msg);
		} finally {
					
			SDDataSource.closeConnection(connectionString, sd);
		}
		
		return response;
		
	}
	
	/*
	 * Get subscription entries
	 */
	@GET
	@Path("/subscriptions")
	@Produces("application/json")
	public Response getSubscriptions(@Context HttpServletRequest request,
			@QueryParam("dt") boolean dt,
			@QueryParam("tz") String tz					// Timezone
			) { 
		
		String connectionString = "API - get subscriptions";
		Response response = null;
		ArrayList<SubItemDt> data = null;
		
		// Authorisation - Access
		Connection sd = SDDataSource.getConnection(connectionString);
		aContacts.isAuthorised(sd, request.getRemoteUser());
		
		tz = (tz == null) ? "UTC" : tz;
		
		try {
	
			// Get the users locale
			Locale locale = new Locale(GeneralUtilityMethods.getUserLanguage(sd, request, request.getRemoteUser()));
			ResourceBundle localisation = ResourceBundle.getBundle("org.smap.sdal.resources.SmapResources", locale);

			ContactManager cm = new ContactManager(localisation);
			data = cm.getSubscriptions(sd, request.getRemoteUser(), tz, dt);
			Gson gson=  new GsonBuilder().disableHtmlEscaping().setDateFormat("yyyy-MM-dd HH:mm:ss").create();
			
			if(dt) {
				SubsDt subs = new SubsDt();
				subs.data = data;
				response = Response.ok(gson.toJson(subs)).build();
			} else {
				response = Response.ok(gson.toJson(data)).build();
			}
			
	
		} catch (Exception e) {
			log.log(Level.SEVERE, "Exception", e);
			response = Response.serverError().build();
		} finally {
					
			SDDataSource.closeConnection(connectionString, sd);
		}
		
		return response;
		
	}


}

