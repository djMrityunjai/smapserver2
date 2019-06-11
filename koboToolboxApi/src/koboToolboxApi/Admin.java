package koboToolboxApi;
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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import managers.AuditManager;
import managers.DataManager;
import model.DataEndPoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.smap.sdal.Utilities.ApplicationException;
import org.smap.sdal.Utilities.Authorise;
import org.smap.sdal.Utilities.GeneralUtilityMethods;
import org.smap.sdal.Utilities.ResultsDataSource;
import org.smap.sdal.Utilities.SDDataSource;
import org.smap.sdal.managers.CustomReportsManager;
import org.smap.sdal.managers.LogManager;
import org.smap.sdal.managers.NotificationManager;
import org.smap.sdal.managers.ProjectManager;
import org.smap.sdal.managers.SubmissionsManager;
import org.smap.sdal.managers.TableDataManager;
import org.smap.sdal.model.Project;
import org.smap.sdal.model.ReportConfig;
import org.smap.sdal.model.SubmissionMessage;
import org.smap.sdal.model.TableColumn;

/*
 * Provides access to various admin services
 */
@Path("/v1/admin")
public class Admin extends Application {

	Authorise a = null;
	Authorise aOwner = null;

	private static Logger log =
			Logger.getLogger(Admin.class.getName());

	LogManager lm = new LogManager();		// Application log

	// Tell class loader about the root classes.  (needed as tomcat6 does not support servlet 3)
	public Set<Class<?>> getClasses() {
		Set<Class<?>> s = new HashSet<Class<?>>();
		s.add(Admin.class);
		return s;
	}

	public Admin() {
		ArrayList<String> authorisations = new ArrayList<String> ();	
		authorisations.add(Authorise.ADMIN);
		a = new Authorise(authorisations, null);
		
		authorisations = new ArrayList<String> ();	
		authorisations.add(Authorise.OWNER);
		aOwner = new Authorise(authorisations, null);
	}

	/*
	 * API version 1 /
	 * Get projects
	 */
	@GET
	@Produces("application/json")
	@Path("/projects")
	public Response getProjects(@Context HttpServletRequest request,
			@QueryParam("all") boolean all,				// If set get all projects for the organisation
			@QueryParam("links") boolean links,			// If set include links to other data that uses the project id as a key
			@QueryParam("tz") String tz					// Timezone
			) throws ApplicationException, Exception { 
		
		Response response = null;
		String connectionString = "kobotoolboxapi-getProjects";
		
		// Authorisation - Access
		Connection sd = SDDataSource.getConnection(connectionString);
		a.isAuthorised(sd, request.getRemoteUser());
		// End Authorisation
		
		ArrayList<Project> projects = null;
		
		try {
			ProjectManager pm = new ProjectManager();
			String urlprefix = GeneralUtilityMethods.getUrlPrefix(request);
			projects = pm.getProjects(sd, request.getRemoteUser(), all, links, urlprefix);
				
			Gson gson = new GsonBuilder().disableHtmlEscaping().create();
			String resp = gson.toJson(projects);
			response = Response.ok(resp).build();
				
		} catch (Exception e) {
			
			log.log(Level.SEVERE,"Error: ", e);
		    response = Response.serverError().build();
		    
		} finally {
			
			SDDataSource.closeConnection(connectionString, sd);
		}

		return response;
	}
	
	/*
	 * API version 1 /
	 * Retry unsent notifications for a specific date range
	 */
	@GET
	@Produces("application/json")
	@Path("/resend_notifications")
	public Response resendNotifications(@Context HttpServletRequest request,
			@QueryParam("startDate") Date startDate,
			@QueryParam("endDate") Date endDate,
			@QueryParam("tz") String tz					// Timezone
			) throws ApplicationException, Exception { 
		
		Response response = null;
		String connectionString = "kobotoolboxapi-resendNotifications";
		
		// Authorisation - Access
		Connection sd = SDDataSource.getConnection(connectionString);
		aOwner.isAuthorised(sd, request.getRemoteUser());
		// End Authorisation
		
		// Check to see if any notifications are enabled for this survey
		String sqlGetNotifications = "select count(*) "
				+ "from forward n "
				+ "where n.s_id = ? " 
				+ "and n.target != 'forward' "
				+ "and n.target != 'document' "
				+ "and n.enabled = 'true' "
				+ "and n.trigger = 'submission'";
		PreparedStatement pstmtGetNotifications = null;
		
		String sql = "select ue_id, user_name, ident, instanceid, p_id "
				+ "from upload_event "
				+ "where upload_time > ? "
				+ "and upload_time < ?";
		PreparedStatement pstmt = null;
		Connection cResults = ResultsDataSource.getConnection(connectionString);
		
		StringBuffer output = new StringBuffer();
		
		String sqlEE = "select exclude_empty from survey where ident = ?";
		PreparedStatement pstmtEE = null;
		
		HashMap<String, String> sentMessages = new HashMap<>();
		Gson gson = new GsonBuilder().disableHtmlEscaping().create();
		String sqlMsg = "select data "
				+ "from message "
				+ "where topic = 'submission' "
				+ "and created_time > ? "
				+ "and created_time < ?";
		PreparedStatement pstmtMsg = null;
		
		try {
			Locale locale = new Locale(GeneralUtilityMethods.getUserLanguage(sd, request, request.getRemoteUser()));
			ResourceBundle localisation = ResourceBundle.getBundle("org.smap.sdal.resources.SmapResources", locale);		
			
			pstmtEE = sd.prepareStatement(sqlEE);
				
			pstmtGetNotifications = sd.prepareStatement(sqlGetNotifications);
			
			// Get the submissions that have already been sent as messages
			pstmtMsg = sd.prepareStatement(sqlMsg);
			pstmtMsg.setDate(1, startDate);
			pstmtMsg.setDate(2, endDate);
			ResultSet rsMsg = pstmtMsg.executeQuery();
			while(rsMsg.next()) {
				String data = rsMsg.getString("data");
				if(data != null) {
					SubmissionMessage msg = gson.fromJson(data, SubmissionMessage.class);
					if(msg != null) {
						sentMessages.put(msg.instanceId, msg.instanceId);
					}
				}
			}

			pstmt = sd.prepareStatement(sql);
			pstmt.setDate(1, startDate);
			pstmt.setDate(2, endDate);
			
			log.info("Get submissions: " + pstmt.toString());
			ResultSet rs = pstmt.executeQuery();
			NotificationManager nm = new NotificationManager(localisation);
			String basePath = GeneralUtilityMethods.getBasePath(request);
			String server = request.getServerName();
			String urlprefix = "https://" + server + "/";
			
			while(rs.next()) {
				
				int ueId = rs.getInt("ue_id");
				String userName = rs.getString("user_name");
				String sIdent = rs.getString("ident");
				String instanceId = rs.getString("instanceid");
				int pId = rs.getInt("p_id");
				int sId = GeneralUtilityMethods.getSurveyId(sd, sIdent);
				
				boolean excludeEmpty = false;
				pstmtEE.setString(1, sIdent);
				ResultSet rsEE = pstmtEE.executeQuery();
				if(rsEE.next()) {
					excludeEmpty = rsEE.getBoolean(1);
				}
				
				output.append("Upload Event: ").append(ueId).append(" ").append(instanceId);
				
				if(sentMessages.get(instanceId) != null) {
					// Already sent
					output.append(":::: Already Sent");
				} else {
					
					// Check to see if notifications are enabled
					pstmtGetNotifications.setInt(1, sId);
					ResultSet rsNot = pstmtGetNotifications.executeQuery();
					if(rsNot.next() && rsNot.getInt(1) > 0) {
									
						nm.notifyForSubmission(
								sd, 
								cResults,
								ueId, 
								userName, 
								"https",
								server,
								basePath,
								urlprefix,
								sIdent,
								instanceId,
								pId,
								excludeEmpty);
						output.append(":::::::::::::::::::::::::::::::::: Notification Resent");
					} else {
						// no enabled notifications
						output.append(":::: No enabled notifications");
					}
				}
				
				output.append("\n");
			}
			response = Response.ok(output.toString()).build();
				
		} catch (Exception e) {
			
			log.log(Level.SEVERE,"Error: ", e);
		    response = Response.serverError().build();
		    
		} finally {
			
			try {if (pstmt != null) {pstmt.close();	}} catch (SQLException e) {	}
			try {if (pstmtEE != null) {pstmtEE.close();	}} catch (SQLException e) {	}
			try {if (pstmtMsg != null) {pstmtMsg.close();	}} catch (SQLException e) {	}
			try {if (pstmtGetNotifications != null) {pstmtGetNotifications.close();	}} catch (SQLException e) {	}
			SDDataSource.closeConnection(connectionString, sd);
			ResultsDataSource.closeConnection(connectionString, cResults);
		}

		return response;
	}


}

