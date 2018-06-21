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

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.smap.sdal.Utilities.Authorise;
import org.smap.sdal.Utilities.GeneralUtilityMethods;
import org.smap.sdal.Utilities.JsonAuthorisationException;
import org.smap.sdal.Utilities.ResultsDataSource;
import org.smap.sdal.Utilities.SDDataSource;
import org.smap.sdal.managers.ExternalFileManager;
import org.smap.sdal.managers.SurveyManager;
import org.smap.sdal.managers.TranslationManager;
import org.smap.sdal.model.Assignment;
import org.smap.sdal.model.Geometry;
import org.smap.sdal.model.KeyValueTask;
import org.smap.sdal.model.ManifestValue;
import org.smap.sdal.model.Organisation;
import org.smap.sdal.model.Project;
import org.smap.sdal.model.Survey;
import org.smap.sdal.model.Task;
import org.smap.sdal.model.TaskAssignment;
import org.smap.sdal.model.TaskLocation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import taskModel.FieldTaskSettings;
import taskModel.FormLocator;
import taskModel.PointEntry;
import taskModel.TaskCompletionInfo;
import taskModel.TaskResponse;

import java.io.File;
import java.lang.reflect.Type;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/*
 * Returns data for the passed in table name
 */
@Path("/myassignments")
public class MyAssignments extends Application {

	Authorise a = new Authorise(null, Authorise.ENUM);

	private static Logger log =
			Logger.getLogger(Survey.class.getName());

	/*
	 * Get assignments for user authenticated with credentials
	 */
	@GET
	@Produces("application/json")
	public Response getTasksCredentials(@Context HttpServletRequest request) throws SQLException {
		log.info("webserviceevent : getTasksCredentials");
		return getTasks(request, request.getRemoteUser());
	}

	/*
	 * Get assignments for user authenticated with a key
	 */
	@GET
	@Produces("application/json")
	@Path("/key/{key}")
	public Response getTaskskey(
			@PathParam("key") String key,
			@Context HttpServletRequest request) throws SQLException {

		log.info("webserviceevent : getTaskskey");

		String user = null;		
		Connection sd = SDDataSource.getConnection("surveyMobileAPI-Upload");

		try {
			user = GeneralUtilityMethods.getDynamicUser(sd, key);
		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			SDDataSource.closeConnection("surveyMobileAPI-Upload", sd);
		}

		if (user == null) {
			log.info("User not found for key");
			throw new JsonAuthorisationException();
		}
		return getTasks(request, user);
	}

	/*
	 * Post assignments for user authenticated with a key
	 */
	@POST
	@Produces("application/json")
	@Path("/key/{key}")
	public Response updateTasksKey(
			@PathParam("key") String key,
			@FormParam("assignInput") String assignInput,
			@Context HttpServletRequest request) {

		log.info("webserviceevent : updateTasksKey");

		String user = null;		
		Connection sd = SDDataSource.getConnection("surveyKPI-UpdateTasksKey");

		try {
			user = GeneralUtilityMethods.getDynamicUser(sd, key);
		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			SDDataSource.closeConnection("surveyKPI-UpdateTasksKey", sd);
		}

		if (user == null) {
			log.info("User not found for key");
			throw new JsonAuthorisationException();
		}

		return updateTasks(request, assignInput, user);
	}

	/*
	 * Update assignments for user authenticated with credentials
	 */
	@POST
	@Produces("application/json")
	public Response updateTasksCredentials(
			@Context HttpServletRequest request, 
			@FormParam("assignInput") String assignInput) {

		log.info("webserviceevent : updateAssignments");
		return updateTasks(request, assignInput, request.getRemoteUser());
	}

	/*
	 * Return the list of tasks allocated to the requesting user
	 */
	public Response getTasks(HttpServletRequest request, String userName) throws SQLException {

		Response response = null;

		TaskResponse tr = new TaskResponse();
		tr.message = "OK Task retrieved";	// Overwritten if there is an error
		tr.status = "200";
		tr.version = 1;

		// Authorisation - Access
		Connection sd = SDDataSource.getConnection("surveyKPI-MyAssignments");
		a.isAuthorised(sd, userName);
		// End Authorisation

		PreparedStatement pstmtGetSettings = null;
		PreparedStatement pstmtGetProjects = null;
		PreparedStatement pstmtGeo = null;
		PreparedStatement pstmt = null;
		PreparedStatement pstmtNumberTasks = null;
		PreparedStatement pstmtDeleteCancelled = null;

		Connection cRel = null;

		int oId = GeneralUtilityMethods.getOrganisationId(sd, userName, 0);

		try {
			// Get the users locale
			Locale locale = new Locale(GeneralUtilityMethods.getUserLanguage(sd, request, request.getRemoteUser()));
			ResourceBundle localisation = ResourceBundle.getBundle("org.smap.sdal.resources.SmapResources", locale);
			
			String sqlDeleteCancelled = "update assignments set status = 'deleted', deleted_date = now() where id = ?";
			pstmtDeleteCancelled = sd.prepareStatement(sqlDeleteCancelled);
			String sqlNumberTasks = "select ft_number_tasks from organisation where id = ?";
			pstmtNumberTasks = sd.prepareStatement(sqlNumberTasks);
			pstmtNumberTasks.setInt(1, oId);
			int ft_number_tasks = 20;
			ResultSet rs = pstmtNumberTasks.executeQuery();
			if(rs.next()) {
				ft_number_tasks = rs.getInt(1);
			}
			
			StringBuffer sql = null;
			boolean superUser = GeneralUtilityMethods.isSuperUser(sd, request.getRemoteUser());

			cRel = ResultsDataSource.getConnection("surveyKPI-MyAssignments");
			sd.setAutoCommit(true);

			// Get the assignments
			sql = new StringBuffer("select "
					+ "t.id as task_id,"
					+ "t.title,"
					+ "t.url,"
					+ "s.ident as form_ident,"
					+ "s.version as form_version,"
					+ "s.p_id as pid,"
					+ "t.initial_data,"
					+ "t.update_id,"
					+ "t.schedule_at,"
					+ "t.location_trigger,"
					+ "t.repeat,"
					+ "a.status as assignment_status,"
					+ "a.id as assignment_id, "
					+ "t.address as address, "
					+ "t.guidance as guidance, "
					+ "ST_AsText(t.geo_point) as geo_point "
					+ "from tasks t, assignments a, users u, survey s, user_project up, project p "
					+ "where t.id = a.task_id "
					+ "and t.form_id = s.s_id "
					+ "and u.id = up.u_id "
					+ "and s.p_id = up.p_id "
					+ "and s.p_id = p.id "
					+ "and s.deleted = 'false' "
					+ "and s.blocked = 'false' "
					+ "and a.assignee = u.id "
					+ "and (a.status = 'cancelled' or a.status = 'accepted' or (a.status = 'submitted' and t.repeat)) "
					+ "and u.ident = ? "
					+ "and p.o_id = ? "
					+ "order by t.schedule_at asc");

			pstmt = sd.prepareStatement(sql.toString());	
			pstmt.setString(1, userName);
			pstmt.setInt(2, oId);

			log.info("Getting assignments: " + pstmt.toString());
			ResultSet resultSet = pstmt.executeQuery();

			int t_id = 0;
			ArrayList<Integer> cancelledAssignments = new ArrayList<Integer> ();
			while (resultSet.next()  && ft_number_tasks > 0) {

				// Create the list of task assignments if it has not already been created
				if(tr.taskAssignments == null) {
					tr.taskAssignments = new ArrayList<TaskAssignment>();
				}

				// Create the new Task Assignment Objects
				TaskAssignment ta = new TaskAssignment();
				ta.task = new Task();
				ta.location = new TaskLocation();
				ta.assignment = new Assignment();

				// Populate the new Task Assignment
				t_id = resultSet.getInt("task_id");
				ta.task.id = t_id;
				ta.task.type = "xform";									// Kept for backward compatibility with old versions of fieldTask
				ta.task.title = resultSet.getString("title");
				ta.task.pid = resultSet.getString("pid");
				ta.task.url = resultSet.getString("url");
				ta.task.form_id = resultSet.getString("form_ident");		// Form id is survey ident
				ta.task.form_version = resultSet.getString("form_version");
				ta.task.initial_data = resultSet.getString("initial_data");
				ta.task.update_id = resultSet.getString("update_id");
				ta.task.scheduled_at = resultSet.getTimestamp("schedule_at");
				ta.task.location_trigger = resultSet.getString("location_trigger");
				if(ta.task.location_trigger != null && ta.task.location_trigger.trim().length() == 0) {
					ta.task.location_trigger = null;
				}
				ta.task.repeat = resultSet.getBoolean("repeat");
				ta.task.address = resultSet.getString("address");
				ta.task.address = addKeyValuePair(ta.task.address, "guidance", resultSet.getString("guidance"));	// Address stored as json key value pairs
				ta.assignment.assignment_id = resultSet.getInt("assignment_id");
				ta.assignment.assignment_status = resultSet.getString("assignment_status");

				String geoString = resultSet.getString("geo_point");
				if(geoString != null) {
					int startIdx = geoString.lastIndexOf('(');
					int endIdx = geoString.indexOf(')');
					if(startIdx > 0 && endIdx > 0) {
						ta.location.geometry = new Geometry();
						String geoString2 = geoString.substring(startIdx + 1, endIdx);
						ta.location.geometry.type = "POINT";
						ta.location.geometry.coordinates = geoString2.split(",");
					}
				}

				// Add the new task assignment to the list of task assignments
				tr.taskAssignments.add(ta);
				
				// Limit the number of non cancelled tasks that can be downloaded
				if(!ta.assignment.assignment_status.equals("cancelled")) {
					ft_number_tasks--;
				} else {
					cancelledAssignments.add(ta.assignment.assignment_id);
				}
			}
			
			/*
			 * Set all tasks to deleted that have been acknowledged as cancelled
			 */
			for(int assignmentId : cancelledAssignments) {
				pstmtDeleteCancelled.setInt(1, assignmentId);
				pstmtDeleteCancelled.executeUpdate();
			}

			/*
			 * Get the complete list of forms accessible by this user
			 */
			SurveyManager sm = new SurveyManager(localisation);
			ArrayList<org.smap.sdal.model.Survey> surveys = sm.getSurveys(sd, pstmt,
					userName,
					false, 
					false, 
					0,
					superUser,
					false,
					false);
			
			TranslationManager translationMgr = new TranslationManager();

			tr.forms = new ArrayList<FormLocator> ();
			
			for (org.smap.sdal.model.Survey survey : surveys) {
				
				boolean hasManifest = translationMgr.hasManifest(sd, request.getRemoteUser(), survey.id);

				if(hasManifest) {
					/*
					 * For each form that has a manifest that links to another form
					 *  generate the new CSV files if the linked data has changed
					 */
					List<ManifestValue> manifestList = translationMgr.
							getSurveyManifests(sd, survey.id, survey.ident, null, 0, true);		// Get linked only
	
					for( ManifestValue m : manifestList) {
	
						String filepath = null;
	
						log.info("Linked file:" + m.fileName);
	
						/*
						 * The file is unique per survey and by user name due to the use of roles to
						 *  restrict columns and rows per user
						 */
						ExternalFileManager efm = new ExternalFileManager(localisation);
						String basepath = GeneralUtilityMethods.getBasePath(request);
						String dirPath = basepath + "/media/" + survey.ident + "/" + userName + "/";
						filepath =  dirPath + m.fileName;
	
						// Make sure the destination exists
						File dir = new File(dirPath);
						dir.mkdirs();
	
						log.info("CSV File is:  " + dirPath + " : directory path created");
	
						efm.createLinkedFile(sd, cRel, survey.id, m.fileName , filepath, userName);
					}
				}

				FormLocator fl = new FormLocator();
				
				fl.ident = survey.ident;
				fl.version = survey.version;
				fl.name = survey.displayName;
				fl.project = survey.projectName;
				fl.pid = survey.pId;
				fl.tasks_only = survey.projectTasksOnly;
				fl.hasManifest = translationMgr.hasManifest(sd, userName, survey.id);

				// If a new manifest then mark the form dirty so it will be checked to see if it needs to be downloaded
				if(hasManifest) {
					fl.dirty = true;
				} else {
					fl.dirty = false;
				}	

				tr.forms.add(fl);
			}

			/*
			 * Get the settings for the phone
			 */
			tr.settings = new FieldTaskSettings();
			sql = new StringBuffer("select "
					+ "o.ft_delete,"
					+ "o.ft_send_location, "
					+ "o.ft_sync_incomplete, "
					+ "o.ft_odk_style_menus, "
					+ "o.ft_specify_instancename, "
					+ "o.ft_admin_menu, "
					+ "o.ft_review_final, "
					+ "o.ft_send "
					+ "from organisation o, users u "
					+ "where u.o_id = o.id "
					+ "and u.ident = ?");

			pstmtGetSettings = sd.prepareStatement(sql.toString());	
			pstmtGetSettings.setString(1, userName);
			log.info("Getting settings: " + pstmtGetSettings.toString());
			resultSet = pstmtGetSettings.executeQuery();

			if(resultSet.next()) {
				tr.settings.ft_delete = resultSet.getString(1);
				tr.settings.ft_delete_submitted = Organisation.get_ft_delete_submitted(tr.settings.ft_delete);
				tr.settings.ft_send_location = resultSet.getString(2);
				tr.settings.ft_sync_incomplete = resultSet.getBoolean(3);
				tr.settings.ft_odk_style_menus = resultSet.getBoolean(4);
				tr.settings.ft_specify_instancename = resultSet.getBoolean(5);
				tr.settings.ft_admin_menu = resultSet.getBoolean(6);
				tr.settings.ft_review_final = resultSet.getBoolean(7);
				tr.settings.ft_send = resultSet.getString(8);
				tr.settings.ft_send_wifi = Organisation.get_ft_send_wifi(tr.settings.ft_send);
				tr.settings.ft_send_wifi_cell = Organisation.get_ft_send_wifi_cell(tr.settings.ft_send);
				tr.settings.ft_location_trigger = GeneralUtilityMethods.isBusinessServer(request.getServerName());
			}

			/*
			 * Get the projects
			 */
			tr.projects = new ArrayList<Project> ();
			sql = new StringBuffer("select p.id, p.name, p.description " +
					" from users u, user_project up, project p " + 
					"where u.id = up.u_id " +
					"and p.id = up.p_id " +
					"and u.ident = ? " +
					"and p.o_id = ? " +
					"order by name ASC;");	

			pstmtGetProjects = sd.prepareStatement(sql.toString());	
			pstmtGetProjects.setString(1, userName);
			pstmtGetProjects.setInt(2, oId);

			log.info("Getting projects: " + pstmtGetProjects.toString());
			resultSet = pstmtGetProjects.executeQuery();

			while(resultSet.next()) {
				Project p = new Project();
				p.id = resultSet.getInt(1);
				p.name = resultSet.getString(2);
				p.desc = resultSet.getString(3);
				tr.projects.add(p);
			}

			/*
			 * Return the response
			 */
			Gson gson = new GsonBuilder().disableHtmlEscaping().setDateFormat("yyyy-MM-dd HH:mm").create();
			String resp = gson.toJson(tr);
			response = Response.ok(resp).build();

		} catch (Exception e) {
			tr.message = "Error: Message=" + e.getMessage();
			tr.status = "400";
			log.log(Level.SEVERE,"", e);
			response = Response.serverError().build();
		} finally {
			try {if (pstmtGetSettings != null) {pstmtGetSettings.close();} } catch (Exception e) {}
			try {if (pstmtGetProjects != null) {pstmtGetProjects.close();} } catch (Exception e) {}
			try {if (pstmtGeo != null) {pstmtGeo.close();} } catch (Exception e) {}
			try {if (pstmt != null) {pstmt.close();} } catch (Exception e) {}
			try {if (pstmtNumberTasks != null) {pstmtNumberTasks.close();} } catch (Exception e) {}
			try {if (pstmtDeleteCancelled != null) {pstmtDeleteCancelled.close();} } catch (Exception e) {}

			SDDataSource.closeConnection("surveyKPI-MyAssignments", sd);
			ResultsDataSource.closeConnection("surveyKPI-MyAssignments", cRel);	
		}

		return response;
	}

	/*
	 * Add a key value pair to an array of key value pairs stored as json
	 */
	String addKeyValuePair(String jIn, String name, String value) {

		
		String jOut = null;
		Gson gson = new GsonBuilder().disableHtmlEscaping().setDateFormat("yyyy-MM-dd HH:mm").create();
		Type type = new TypeToken<ArrayList<KeyValueTask>>(){}.getType();

		ArrayList<KeyValueTask> kvArray = null;

		// 1. Get the current array
		if(jIn != null && jIn.trim().length() > 0 && !jIn.equals("[ ]")) {
			kvArray = new Gson().fromJson(jIn, type);
		} else {
			kvArray = new ArrayList<KeyValueTask> ();
		}
		if(name != null && value != null && !value.equals("[ ]")) {
			// 2. Add the new kv pair
			if(value != null && value.trim().length() > 0 ) {
				KeyValueTask newKV = new KeyValueTask(name, value);
				kvArray.add(newKV);
			}
		}

		// 3. Return the updated list
		jOut = gson.toJson(kvArray);
		return jOut;
	}

	/*
	 * Update the task assignment
	 */
	public Response updateTasks(@Context HttpServletRequest request, 
			String assignInput,
			String userName) { 

		Response response = null;

		Connection sd = SDDataSource.getConnection("surveyKPI-MyAssignments");

		// Authorisation not required a user can only update their own assignments

		TaskResponse tr = new Gson().fromJson(assignInput, TaskResponse.class);

		log.info("Device:" + tr.deviceId + " for user " + userName);

		// TODO that the status is valid (A different range of status values depending on the role of the user)

		PreparedStatement pstmt = null;
		PreparedStatement pstmtForms = null;
		PreparedStatement pstmtFormsDelete = null;
		PreparedStatement pstmtUser = null;
		PreparedStatement pstmtTasks = null;
		PreparedStatement pstmtTrail = null;
		PreparedStatement pstmtRepeats = null;
		try {
			String sql = null;

			String sqlRepeats = "update tasks set repeat_count = repeat_count + 1 " +
					"where id = (select task_id from assignments where id = ?);";
			pstmtRepeats = sd.prepareStatement(sqlRepeats);

			sd.setAutoCommit(false);
			for(TaskAssignment ta : tr.taskAssignments) {
				if(ta.assignment.assignment_id > 0) {
					log.info("Task Assignment: " + ta.assignment.assignment_status);

					/*
					 * If the updated status = "cancelled" then this is an acknowledgment of the status set on the server
					 *   hence update the server status to "deleted"
					 */
					if(ta.assignment.assignment_status.equals("cancelled")) {
						log.info("Assignment:" + ta.assignment.assignment_id + " acknowledge cancel");

						sql = "update assignments a set status = 'deleted', deleted_date = now() "
								+ "where a.id = ? "
								+ "and a.assignee in (select id from users u "
								+ "where u.ident = ?)";
						pstmt = sd.prepareStatement(sql);	
						pstmt.setInt(1, ta.assignment.assignment_id);
						pstmt.setString(2, userName);
					} else {

						// Apply update making sure the assignment was made to the updating user
						sql = "UPDATE assignments a SET status = ? " +
								"where a.id = ? " + 
								"and a.assignee in (select id from users u " +
								"where u.ident = ?)";
						pstmt = sd.prepareStatement(sql);
						pstmt.setString(1, ta.assignment.assignment_status);
						pstmt.setInt(2, ta.assignment.assignment_id);
						pstmt.setString(3, userName);

						if(ta.assignment.assignment_status.equals("submitted")) {
							pstmtRepeats.setInt(1, ta.assignment.assignment_id);
							log.info("Updating task repeats: " + pstmtRepeats.toString());
							pstmtRepeats.executeUpdate();
						}
					}

					log.info("update assignments: " + pstmt.toString());
					pstmt.executeUpdate();
				}
			}

			/*
			 * Update the status of down loaded forms
			 */
			sql = "select id from users where ident = ?"; 
			pstmtUser = sd.prepareStatement(sql);
			pstmtUser.setString(1, userName);
			ResultSet rs = pstmtUser.executeQuery();
			if(rs.next()) {
				int userId = rs.getInt(1);

				/*
				 * Record task information for any submitted tasks
				 */
				if(tr.taskCompletionInfo != null) {
					sql = "insert into task_completion (" +
							"u_id, " +
							"device_id, " +
							"form_ident, " +
							"form_version, " +
							"uuid,"	+
							"the_geom," +
							"completion_time" +
							") " +
							"values(?, ?, ?, ?, ?, ST_GeomFromText(?, 4326), ?);";
					pstmtTasks = sd.prepareStatement(sql);
					pstmtTasks.setInt(1, userId);
					pstmtTasks.setString(2, tr.deviceId);
					for(TaskCompletionInfo tci : tr.taskCompletionInfo) {

						pstmtTasks.setString(3, tci.ident);
						pstmtTasks.setInt(4, tci.version);
						pstmtTasks.setString(5, tci.uuid);
						pstmtTasks.setString(6, "POINT(" + tci.lon + " " + tci.lat + ")");
						pstmtTasks.setTimestamp(7, new Timestamp(tci.actFinish));

						log.info("Insert task: " + pstmtTasks.toString());
						pstmtTasks.executeUpdate();
					}

				}

				/*
				 * Record user trail information
				 */
				if(tr.userTrail != null) {
					sql = "insert into user_trail (" +
							"u_id, " +
							"device_id, " +			
							"the_geom," +
							"event_time" +
							") " +
							"values(?, ?, ST_GeomFromText(?, 4326), ?);";
					pstmtTrail = sd.prepareStatement(sql);
					pstmtTrail.setInt(1, userId);
					pstmtTrail.setString(2, tr.deviceId);
					for(PointEntry pe : tr.userTrail) {

						pstmtTrail.setString(3, "POINT(" + pe.lon + " " + pe.lat + ")");
						pstmtTrail.setTimestamp(4, new Timestamp(pe.time));

						pstmtTrail.executeUpdate();
					}

				}
			}

			sd.commit();
			response = Response.ok().build();
			log.info("Assignments updated");	

		} catch (Exception e) {		
			response = Response.serverError().build();
			log.log(Level.SEVERE,"Exception", e);
			try { sd.rollback();} catch (Exception ex){log.log(Level.SEVERE,"", ex);}
		} finally {

			try {if ( pstmt != null ) { pstmt.close(); }} catch (Exception e) {}
			try {if ( pstmtForms != null ) { pstmtForms.close(); }} catch (Exception e) {}
			try {if ( pstmtFormsDelete != null ) { pstmtFormsDelete.close(); }} catch (Exception e) {}
			try {if ( pstmtUser != null ) { pstmtUser.close(); }} catch (Exception e) {}
			try {if ( pstmtTasks != null ) { pstmtTasks.close(); }} catch (Exception e) {}
			try {if ( pstmtTrail != null ) { pstmtTrail.close(); }} catch (Exception e) {}
			try {if ( pstmtRepeats != null ) { pstmtRepeats.close(); }} catch (Exception e) {}

			SDDataSource.closeConnection("surveyKPI-MyAssignments", sd);
		}

		return response;
	}
}



