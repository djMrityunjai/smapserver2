/*****************************************************************************

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

 ******************************************************************************/

package org.smap.subscribers;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import javax.ws.rs.core.Response;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.io.FileUtils;
import org.smap.model.IE;
import org.smap.model.SurveyInstance;
import org.smap.model.SurveyTemplate;
import org.smap.sdal.Utilities.Authorise;
import org.smap.sdal.Utilities.GeneralUtilityMethods;
import org.smap.sdal.managers.NotificationManager;
import org.smap.sdal.managers.SurveyManager;
import org.smap.sdal.model.ChangeItem;
import org.smap.sdal.model.Survey;
import org.smap.server.entities.Form;
import org.smap.server.entities.Option;
import org.smap.server.entities.Question;
import org.smap.server.entities.SubscriberEvent;
import org.smap.server.exceptions.SQLInsertException;
import org.smap.server.utilities.UtilityMethods;
import org.w3c.dom.Document;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;


public class SubRelationalDB extends Subscriber {

	/*
	 * Class to store information about geometry columns
	 * Needed to support two phase creation of geometry columns in tables
	 */
	private class GeometryColumn {
		public String tableName = null;
		public String columnName = null;
		public String srid = "4326";
		public String type = null;
		public String dimension = "2";
		
		public GeometryColumn(String tableName, String columnName, String type) {
			this.tableName = tableName;
			this.columnName = columnName;
			this.type = type;
		}
	}
	
	private class Keys {
		ArrayList<Integer> duplicateKeys = new ArrayList<Integer>();
		int newKey = 0;
	}

	// Details of survey definitions database
	String dbClassMeta = null;
	String databaseMeta = null;
	String userMeta = null;
	String passwordMeta = null;
	
	// Details for results database
	String dbClass = null;
	String database = null;
	String user = null;
	String password = null;
	
	String gBasePath = null;
	String gFilePath = null;

	/**
	 * @param args
	 */
	public SubRelationalDB() {
		super();

	}
	
	@Override
	public String getDest() {
		return "upload";
		
	}
	
	@Override
	public void upload(SurveyInstance instance, InputStream is, String remoteUser, 
			String server, String device, SubscriberEvent se, String confFilePath, String formStatus,
			String basePath, String filePath, String updateId, int ue_id)  {
		
		gBasePath = basePath;
		gFilePath = filePath;

		if(gBasePath == null || gBasePath.equals("/ebs1")) {
			gBasePath = "/ebs1/servers/" + server.toLowerCase();
		}
		formStatus = (formStatus == null) ? "complete" : formStatus;
		
		System.out.println("base path: " + gBasePath);
		// Open the configuration file
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = null;
		Document xmlConf = null;		
		Connection connection = null;
		Survey survey = null;
		try {
				
			// Get the connection details for the database with survey definitions
			db = dbf.newDocumentBuilder();
			xmlConf = db.parse(new File(confFilePath + "/metaDataModel.xml"));
			dbClassMeta = xmlConf.getElementsByTagName("dbclass").item(0).getTextContent();
			databaseMeta = xmlConf.getElementsByTagName("database").item(0).getTextContent();
			userMeta = xmlConf.getElementsByTagName("user").item(0).getTextContent();
			passwordMeta = xmlConf.getElementsByTagName("password").item(0).getTextContent();
			
			// Get the connection details for the target results database
			xmlConf = db.parse(new File(confFilePath + "/" + getSubscriberName() + ".xml"));
			dbClass = xmlConf.getElementsByTagName("dbclass").item(0).getTextContent();
			database = xmlConf.getElementsByTagName("database").item(0).getTextContent();
			user = xmlConf.getElementsByTagName("user").item(0).getTextContent();
			password = xmlConf.getElementsByTagName("password").item(0).getTextContent();
			
			/*
			 * Verify that the survey is valid for the submitting user
			 *  Only do this if the survey id is numeric otherwise it is an old style
			 *  survey and it will be ignored as eventually there will be no surveys identified by name
			 */
			
			String templateName = instance.getTemplateName();

			// Authorisation - Access
		    Class.forName(dbClassMeta);		 
			connection = DriverManager.getConnection(databaseMeta, userMeta, passwordMeta);
			//Authorise a = new Authorise(null, Authorise.ENUM);
			SurveyManager sm = new SurveyManager();
			survey = sm.getSurveyId(connection, templateName);	// Get the survey from the templateName / ident

			try {
				if (connection != null) {
					connection.close();
				}
			} catch (SQLException e) {
				System.out.println("Failed to close connection");
			    e.printStackTrace();
			}
		
			
		} catch (Exception e) {
			e.printStackTrace();
			se.setStatus("error");
			se.setReason("Configuration File:" + e.getMessage());
			return;
		}		

		try {
			
			writeAllTableContent(instance, remoteUser, server, device, formStatus, updateId, survey.id);
			applyNotifications(ue_id, remoteUser, server);
			applyAssignmentStatus(ue_id, remoteUser);
			se.setStatus("success");			
			
		} catch (SQLInsertException e) {
			
			se.setStatus("error");
			se.setReason(e.getMessage());
			
		}
			
		return;
	}
	
	/*
	 * Apply any changes to assignment status
	 */
	private void applyAssignmentStatus(int ue_id, String remoteUser) {
		
		Connection connectionSD = null;
		PreparedStatement pstmt = null;
		PreparedStatement pstmtGetUploadEvent = null;
		
		String sqlGetUploadEvent = "select ue.assignment_id " +
				" from upload_event ue " +
				" where ue.ue_id = ? and ue.assignment_id is not null;";
		

		String sql = "UPDATE assignments a SET status = 'submitted' " +
				"where a.id = ? " + 
				"and a.assignee IN (SELECT id FROM users u " +
					"where u.ident = ?);";
		
		
		try {
			connectionSD = DriverManager.getConnection(databaseMeta, user, password);
			
			pstmtGetUploadEvent = connectionSD.prepareStatement(sqlGetUploadEvent);
			pstmt = connectionSD.prepareStatement(sql);
 		
			pstmtGetUploadEvent.setInt(1, ue_id);
			ResultSet rs = pstmtGetUploadEvent.executeQuery();
			
			if(rs.next()) {
				int assignment_id = rs.getInt(1);
				if(assignment_id > 0) {
					pstmt.setInt(1, assignment_id);
					pstmt.setString(2, remoteUser);
					System.out.println("Updating assignment status: " + pstmt.toString());
					pstmt.executeUpdate();
				}
				

			}
			
			
		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			
			try {if (pstmt != null) {pstmt.close();}} catch (SQLException e) {}
			try {if (pstmtGetUploadEvent != null) {pstmtGetUploadEvent.close();}} catch (SQLException e) {}
			
			try {
				if (connectionSD != null) {
					connectionSD.close();
					connectionSD = null;
				}
			} catch (SQLException e) {
				e.printStackTrace();
			}
		
		}
	}
		
	
	
	/*
	 * Apply notifications
	 */
	private void applyNotifications(int ue_id, String remoteUser, String server) {
		
		PreparedStatement pstmtGetUploadEvent = null;
		PreparedStatement pstmtGetNotifications = null;
		PreparedStatement pstmtUpdateUploadEvent = null;
		PreparedStatement pstmtNotificationLog = null;
		
		Connection connectionSD = null;
		Connection cResults = null;
		
		try {
			Class.forName(dbClass);	 
			connectionSD = DriverManager.getConnection(databaseMeta, user, password);
			cResults = DriverManager.getConnection(database, user, password);
		
			NotificationManager fm = new NotificationManager();
			fm.notifyForSubmission(
					connectionSD, 
					cResults,
					pstmtGetUploadEvent, 
					pstmtGetNotifications, 
					pstmtUpdateUploadEvent, 
					pstmtNotificationLog, 
					ue_id, 
					remoteUser, 
					server,
					gBasePath);	
			
		} catch (SQLException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			
			try {if (pstmtGetUploadEvent != null) {pstmtGetUploadEvent.close();}} catch (SQLException e) {}
			try {if (pstmtGetNotifications != null) {pstmtGetNotifications.close();}} catch (SQLException e) {}
			try {if (pstmtUpdateUploadEvent != null) {pstmtUpdateUploadEvent.close();}} catch (SQLException e) {}
			try {if (pstmtNotificationLog != null) {pstmtNotificationLog.close();}} catch (SQLException e) {}
			
			try {
				if (connectionSD != null) {
					connectionSD.close();
					connectionSD = null;
				}
			} catch (SQLException e) {
				e.printStackTrace();
			}
			
			try {
				if (cResults != null) {
					cResults.close();
					cResults = null;
				}
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
	}
	
	/*
	 * Write the submission to the database
	 */
	private void writeAllTableContent(SurveyInstance instance, String remoteUser, 
			String server, String device, String formStatus, String updateId,
			int sId) throws SQLInsertException {
			
		String response = null;
		Connection cResults = null;
		Connection cMeta = null;
		Statement statement = null;
		
		try {
		    Class.forName(dbClass);	 
			cResults = DriverManager.getConnection(database, user, password);
			cMeta = DriverManager.getConnection(databaseMeta, user, password);
			
			cResults.setAutoCommit(false);
			statement = cResults.createStatement();
			IE topElement = instance.getTopElement();
			
			// Make sure the top element matched a form in the template
			if(topElement.getType() == null) {
				String msg = "Error: Top element name " + topElement.getName() + " in survey did not match a form in the template";
				throw new Exception(msg);
			}
			Keys keys = writeTableContent(
					topElement, 
					statement, 
					0, 
					instance.getTemplateName(), 
					remoteUser, 
					server, 
					device, 
					instance.getUuid(), 
					formStatus, 
					instance.getVersion(), 
					cResults,
					cMeta,
					sId);

			// 
			if(keys.duplicateKeys.size() > 0) {
				/*
				 * Bug fix - duplicates
				 *
				if((formStatus != null && 
						(formStatus.equals("draft") || formStatus.equals("incomplete"))) ||
						getDuplicatePolicy() == DUPLICATE_REPLACE) {
					System.out.println("Replacing existing record with " + keys.newKey);
					replaceExistingRecord(cResults, cMeta, topElement, keys.duplicateKeys, keys.newKey);		// Mark the existing record as being replaced
				} else {
				*/
					System.out.println("Dropping duplicate");
				//}
			} else {
				// Check to see this submission was set to update an existing record with new data
				System.out.println("Check for existing key");
				
				String existingKey = null;
				if(updateId != null) {
					System.out.println("Existing unique id:" + updateId);
					existingKey = getKeyFromId(cResults, topElement, updateId);
				} else {		
					existingKey = topElement.getKey(); 	// Old way of checking for updates - deprecate
				}
				
				if(existingKey != null) {
					System.out.println("Existing key:" + existingKey);
					ArrayList<Integer> existingKeys = new ArrayList<Integer>();
					existingKeys.add(Integer.parseInt(existingKey));
					replaceExistingRecord(cResults, cMeta, topElement,existingKeys , keys.newKey);		// Mark the existing record as being replaced
				}
				
			}
			cResults.commit();
			cResults.setAutoCommit(true);

		} catch (Exception e) {
			if(cResults != null) {
				try {
					
					response = "Error: Rolling back: " + e.getMessage();
					e.printStackTrace();
					System.out.println("        " + response);
					cResults.rollback();
					cResults.setAutoCommit(true);
					throw new SQLInsertException(e.getMessage());
					
				} catch (SQLException ex) {
					
					System.out.println(ex.getMessage());
					throw new SQLInsertException(e.getMessage());
					
				}

			} else {
				
				String mesg = "Error: Connection to the database is null";
				System.out.println("        " + mesg);
				throw new SQLInsertException(mesg);
				
			}
			
		} finally {
			try { if (statement != null) { statement.close(); } } catch (Exception e) {}
			try {
				if (cResults != null) {
					cResults.close();
				}
			} catch (SQLException e) {
				System.out.println("Failed to close connection");
			    e.printStackTrace();
			}
			try {
				if (cMeta != null) {
					cMeta.close();
				}
			} catch (SQLException e) {
				System.out.println("Failed to close connection");
			    e.printStackTrace();
			}
		}		
	}

	/*
	 * Method to write the table content
	 */
	private  Keys writeTableContent(
			IE element, 
			Statement statement, 
			int parent_key, 
			String sName, 
			String remoteUser, 
			String server, 
			String device, 
			String uuid, 
			String formStatus, 
			int version,
			Connection cRel,
			Connection cMeta,
			int sId) throws SQLException, Exception {

		Keys keys = new Keys();
		
		/*
		 * Write the Instance element to a table if it is a form type
		 * The sub elements of a complex question will be written to their own table
		 *  as well as being handled as a composite/complex question by the parent form
		 */
		if(element.getType() != null && (element.getType().equals("form") 
				|| (element.getQType() != null && element.getQType().equals("geopolygon"))
				|| (element.getQType() != null && element.getQType().equals("geolinestring"))
				)) {
			
			// Write form
			String tableName = element.getTableName();
			List<IE> columns = element.getQuestions();
			String sql = null;	
			
			/*
			 * If this is the top level form then 
			 *   1) create all the tables for this survey if they do not already exist
			 *   2) Check if this survey is a duplicate
			 */
			keys.duplicateKeys = new ArrayList<Integer>();
			if(parent_key == 0) {	// top level survey has a parent key of 0
				boolean tableCreated = createTable(statement, tableName, sName);
				keys.duplicateKeys = checkDuplicate(statement, tableName, uuid);
				/*
				 * Bug fix duplicates
				 *
				if(keys.duplicateKeys.size() > 0 && 
						getDuplicatePolicy() == DUPLICATE_DROP && 
						formStatus.equals("complete")) {
					throw new Exception("Duplicate survey: " + uuid);
				}
				*/
				if(keys.duplicateKeys.size() > 0 && 
						getDuplicatePolicy() == DUPLICATE_DROP) {
					throw new Exception("Duplicate survey: " + uuid);
				}
				// Apply any updates that have been made to the table structure since the last submission
				if(tableCreated) {
					markAllChangesApplied(cMeta, sId);
				} else {
					System.out.println("Pre-applyTableChanges: Auto commit for results is: " + cRel.getAutoCommit());
					applyTableChanges(cMeta, cRel, sId);
					System.out.println("Post-applyTableChanges: Auto commit for results is: " + cRel.getAutoCommit());
				}
				markPublished(cMeta, sId);
			}
			
			boolean isBad = false;
			String complete = "true";
			String bad_reason = null;
			if(formStatus != null && (formStatus.equals("incomplete") || formStatus.equals("draft"))) {
				isBad = true;
				bad_reason = "incomplete";
				complete = "false";
			}
			
			/*
			 * Write the record
			 */
			if(columns.size() > 0) {
				
				boolean hasVersion = hasVersion(cRel, tableName);
				sql = "INSERT INTO " + tableName + " (parkey";
				if(parent_key == 0) {
					sql += ",_user, _complete";	// Add remote user, _complete automatically (top level table only)
					if(hasVersion) {
						sql += ",_version";
					}
					if(isBad) {
						sql += ",_bad, _bad_reason";
					}
				}

				sql += addSqlColumns(columns);
				
				sql += ") VALUES (" + parent_key;
				if(parent_key == 0) {
					sql += ",'" + remoteUser + "', '" + complete + "'";	
					if(hasVersion) {
						sql += "," + version;
					}
					if(isBad) {
						sql += ",'true','" + bad_reason + "'";
					}
				}
				sql += addSqlValues(columns, sName, device, server, false);
				sql += ");";
				
				System.out.println("        SQL statement: " + sql);
				
				statement.execute(sql, Statement.RETURN_GENERATED_KEYS);
				ResultSet rs = statement.getGeneratedKeys();
				if( rs.next()) {
					parent_key = rs.getInt(1);
					keys.newKey = parent_key;
				}
				
			}
		}
		
		//Write any child forms
		List<IE> childElements = element.getChildren();
		for(IE child : childElements) {
			writeTableContent(child, statement, parent_key, sName, remoteUser, server, device, 
					uuid, formStatus, version, cRel, cMeta, sId);
		}
		
		return keys;

	}
	
	/*
	 * Method to check for presence of a version column
	 */
	boolean hasVersion(Connection cRel, String tablename)  {
		
		boolean hasVersion = false;
		
		String sql = "select column_name " +
					"from information_schema.columns " +
					"where table_name = ? and column_name = '_version';";
		
		PreparedStatement pstmt = null;
		try {
			pstmt = cRel.prepareStatement(sql);
			pstmt.setString(1, tablename);
			System.out.println("SQL: " + pstmt.toString());
			
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				hasVersion = true;
			}

		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {if (pstmt != null) {pstmt.close();}} catch (Exception e) {}
		}
		
		return hasVersion;
	}
	/*
	 * Method to replace an existing record
	 */
	private  void replaceExistingRecord(Connection cRel, Connection cMeta, IE element, ArrayList<Integer> existingKeys, long newKey) throws SQLException, Exception {

		/*
		 * Set the record as bad with the reason being that it has been replaced
		 */		
		String tableName = element.getTableName();
		
		// Check that the new record is not bad
		String sql = "select _bad from " + tableName + " where prikey = ?;";
		boolean isGood = false;
		PreparedStatement pstmt = cRel.prepareStatement(sql);
		pstmt = cRel.prepareStatement(sql);
		pstmt.setLong(1, newKey);
		ResultSet rs = pstmt.executeQuery();
		if(rs.next()) {
			isGood = !rs.getBoolean(1);
		}
		pstmt.close();
		
		if(isGood) {
			// Get the survey id and form id for this table
			String bad_reason = "Replaced by " + newKey;
			int s_id;
			int f_id;
			sql = "select f.s_id, f.f_id from form f where f.table_name = ?;";

			pstmt = cMeta.prepareStatement(sql);
			pstmt.setString(1, tableName);
			rs = pstmt.executeQuery();
			if(rs.next()) {
				s_id = rs.getInt(1);
				f_id = rs.getInt(2);
				
				// Mark the records replaced
				for(int i = 0; i < existingKeys.size(); i++) {	
					int dupKey = existingKeys.get(i);
					org.smap.sdal.Utilities.UtilityMethodsEmail.markRecord(cRel, cMeta, tableName, 
							true, bad_reason, dupKey, s_id, f_id, true, false);
				}
			}
			pstmt.close();		

		}
	
	}

	/*
	 * Get the primary key from the unique instance id
	 */
	private  String getKeyFromId(Connection connection, IE element, String instanceId) throws SQLException {

		String key = null;
		/*
		 * Set the record as bad with the reason being that it has been replaced
		 */		
		String tableName = element.getTableName();
		
		// Check that the new record is not bad
		String sql = "select _bad, prikey from " + tableName + " where instanceid = ?;";
		boolean isGood = false;
		PreparedStatement pstmt = connection.prepareStatement(sql);
		pstmt = connection.prepareStatement(sql);
		pstmt.setString(1, instanceId);
		ResultSet rs = pstmt.executeQuery();
		if(rs.next()) {
			isGood = !rs.getBoolean(1);
			key = rs.getString(2);
		}
		pstmt.close();
		
		return key;
	
	}

	/*
	 * Generate the sql for the column names
	 */
	String addSqlColumns(List<IE> columns) {
		String sql = "";
		
		for(IE col : columns) {
			String colType = col.getQType();
			if(colType.equals("select")) {
				List<IE> options = col.getChildren();
				UtilityMethods.sortElements(options);
				for(IE option : options) {
					sql += "," + option.getColumnName();
				}
			} else if(colType.equals("geopolygon") || colType.equals("geolinestring") || colType.equals("geopoint")
					|| colType.equals("geoshape") || colType.equals("geotrace")) {
				// All geospatial columns have the name "the_geom"
				sql += "," + "the_geom";
			} else if(colType.equals("begin group")) {
				// Non repeating group, process these child columns at the same level as the parent
				sql += addSqlColumns(col.getQuestions());
			} else {
				String colName = col.getColumnName();
				sql += "," + colName;
			}				
		}
		
		return sql;
	}
	
	String addSqlValues(List<IE> columns, String sName, String device, String server, boolean phoneOnly) {
		String sql = "";
		for(IE col : columns) {
			boolean colPhoneOnly = phoneOnly || col.isPhoneOnly();	// Set phone only if the group is phone only or just this column
			String colType = col.getQType();
			
			if(colType.equals("select")) {
				List<IE> options = col.getChildren();
				UtilityMethods.sortElements(options);
				for(IE option : options) {
					sql += "," + (colPhoneOnly ? "" : option.getValue());
				}
			} else if(colType.equals("begin group")) {
				// Non repeating group, process these child columns at the same level as the parent
				sql += addSqlValues(col.getQuestions(), sName, device, server, colPhoneOnly);
			} else {
				sql += "," + getDbString(col, sName, device, server, colPhoneOnly);
			}				
		}
		return sql;
	}
	
	/*
	 * Format the value into a string appropriate to its type
	 */
	String getDbString(IE col, String surveyName, String device, String server, boolean phoneOnly) {
		
		String qType = col.getQType();
		String value = col.getValue();	// Escape quotes and trim
		String colName = col.getName();
		
		/*
		 * Debug utf-8 print
		 *
		try {
			new PrintStream(System.out, true, "UTF-8").println("value: " + value);
		} catch (UnsupportedEncodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		*/
		
		// If the deviceId is not in the form results add it from the form meta data
		if(colName != null && colName.equals("_device")) {
			if(value == null || value.trim().length() == 0) {
				value = device;
			}
		}
		
		if(phoneOnly) {
			if(qType.equals("string")) {
				value = "'xxxx'";			// Provide feedback to user that this value was withheld
			} else {
				value = "null";				// For non string types will just set to null
			}
		} else {
		
			if(value != null) {				
	
				value = value.replace("'", "''").trim();
						
				if(qType.equals("string") || qType.equals("select1") || qType.equals("barcode")) {
					value = "'" + value + "'";
					
				} else if(qType.equals("int") || qType.equals("decimal")) {
					if(value.length() == 0) {
						value = "null";
					}
					
				} else if(qType.equals("date") || qType.equals("dateTime") || qType.equals("time") ) {
					if(value.length() > 0) {
						value = "'" + col.getValue() + "'";
					} else {
						value = "null";
					}
					
				} else if(qType.equals("geopoint")) {
					// Geo point parameters are separated by a space and in the order Y X
					// To store as a Point in the db this order needs to be reversed
					String params[] = value.split(" ");
					if(params.length > 1) {
						value = "ST_GeomFromText('POINT(" + params[1] + " " + params[0] + ")', 4326)";
					} else {
						System.out.println("Error: Invalid geometry point detected: " + value);
						value = "null";
					}
					
				} else if(qType.equals("audio") || qType.equals("video") || qType.equals("image")) {
				
					System.out.println("Processing media. Value: " + value);
					if(value == null || value.length() == 0) {
						value = "null";
					} else {
						/*
						 * If this is a new file then rename the attachment to use a UUID
						 * Where this is an update to an existing survey and the file has not been re-submitted then 
						 * leave its value unchanged
						 */
						String srcName = value;
						
						try {
							new PrintStream(System.out, true, "UTF-8").println("Creating file: " + srcName);
						} catch (UnsupportedEncodingException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
						
						File srcXmlFile = new File(gFilePath);
						File srcXmlDirFile = srcXmlFile.getParentFile();
						File srcPathFile = new File(srcXmlDirFile.getAbsolutePath() + "/" + srcName);
						
						if(srcPathFile.exists()) {
							value = "'" + GeneralUtilityMethods.createAttachments(
									srcName, 
									srcPathFile, 
									gBasePath, 
									surveyName) + "'";
	
						} else {
							try {
								new PrintStream(System.out, true, "UTF-8").println("Source file does not exist: " + srcPathFile.getAbsolutePath());
							} catch (UnsupportedEncodingException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
							value = "'" + value + "'";
						}
						
					}
				} else if(qType.equals("geoshape") || qType.equals("geotrace")) {
					/*
					 * ODK polygon / linestring
					 * The coordinates are in a String separated by ;
					 * Each coordinate consists of space separated lat lon height accuracy
					 *   Actually I'm not sure about the last two but they will be ignored anyway
					 *   To store as a Point in the db this order needs to be reversed to (lon lat)
					 */
					int min_points = 3;
					StringBuffer ptString = null;
					if(qType.equals("geotrace")) {
						min_points = 2;
					}
					
					String coords[] = value.split(";");
					if(coords.length >= min_points) {
						if(qType.equals("geoshape")) {
								ptString = new StringBuffer("ST_GeomFromText('POLYGON((");
						} else {
							ptString = new StringBuffer("ST_GeomFromText('LINESTRING(");
						}
						for(int i = 0; i < coords.length; i++) {
							String [] points = coords[i].split(" ");
							if(points.length > 1) {
								if(i > 0) {
									ptString.append(",");
								}
								ptString.append(points[1]);
								ptString.append(" ");
								ptString.append(points[0]);
							} else {
								System.out.println("Error: " + qType + " Badly formed point." + coords[i]);
							}
						}
						if(qType.equals("geoshape")) {
							ptString.append("))', 4326)");
						} else {
							ptString.append(")', 4326)");
						}
						value = ptString.toString();
						
					} else {
						value = "null";
						System.out.println("Error: " + qType + " Insufficient points for " + qType + ": " + coords.length);
					}
					System.out.println("Value for geoshape: " + value);
					
				} else if(qType.equals("geopolygon") || qType.equals("geolinestring")) {
					// Complex types
					IE firstPoint = null;
					String ptString = "";
					List<IE> points = col.getChildren();
					int number_points = points.size();
					if(number_points < 3 && qType.equals("geopolygon")) {
						value = "null";
						System.out.println("Error: Insufficient points for polygon." + number_points);
					} else if(number_points < 2 && qType.equals("geolinestring")) {
						value = "null";
						System.out.println("Error: Insufficient points for line." + number_points);
					} else {
						for(IE point : points) {
							
							String params[] = point.getValue().split(" ");
							if(params.length > 1) {
								if(firstPoint == null) {
									firstPoint = point;		// Used to loop back for a polygon
								} else {
									ptString += ",";
								}
								ptString += params[1] + " " + params[0];
							}
						}
						if(ptString.length() > 0) {
							if(qType.equals("geopolygon")) {
								if(firstPoint != null) {
									String params[] = firstPoint.getValue().split(" ");
									if(params.length > 1) {
										ptString += "," + params[1] + " " + params[0];
									}
								}
								value = "ST_GeomFromText('POLYGON((" + ptString + "))', 4326)";
							} else if(qType.equals("geolinestring")) {
								value = "ST_GeomFromText('LINESTRING(" + ptString + ")', 4326)";
							}
						} else {
							value = "null";
						}
					}
					
				}
			} else {
				value = "null";
			}
		}
		
		return value;
	}
	
	
	/*
	 * Create the table if it does not already exits in the database
	 */
	private boolean createTable(Statement statement, String tableName, String sName) {
		boolean tableCreated = false;
		String sql = "select count(*) from information_schema.tables where table_name ='" + tableName + "';";
		
		System.out.println("SQL:" + sql);
		try {
			ResultSet res = statement.executeQuery(sql);
			int count = 0;
			
			if(res.next()) {
				count = res.getInt(1);
			}
			if(count > 0) {
				System.out.println("        Table Exists");
			} else {
				System.out.println("        Table does not exist");
				SurveyTemplate template = new SurveyTemplate();   
				template.readDatabase(sName);	
				writeAllTableStructures(template);
				tableCreated = true;
			}
		} catch (Exception e) {
			System.out.println("        Error checking for existence of table:" + e.getMessage());
		}
		return tableCreated;
	}
	
	/*
	 * Create the tables for the survey
	 */
	private void writeAllTableStructures(SurveyTemplate template) {
			
		String response = null;
		Connection connection = null;
		
		try {
		    Class.forName(dbClass);	 
			connection = DriverManager.getConnection(database, user, password);
			Statement statement = connection.createStatement();
			
			List<Form> forms = template.getAllForms();	
			connection.setAutoCommit(false);
			for(Form form : forms) {		
				writeTableStructure(form, statement);
				connection.commit();
			}	
			connection.setAutoCommit(true);
	


		} catch (Exception e) {
			if(connection != null) {
				try {
					response = "Error: Rolling back: " + e.getMessage();	// TODO can't roll back within higher level transaction
					System.out.println(response);
					e.printStackTrace();
					connection.rollback();
					connection.setAutoCommit(true);
				} catch (SQLException ex) {
					System.out.println(ex.getMessage());
				}

			}
			
		} finally {
			try {
				if (connection != null) {
					connection.close();
				}
			} catch (SQLException e) {
				System.out.println("Failed to close connection");
			    e.printStackTrace();
			}
		}		
	}
	
	private void writeTableStructure(Form form, Statement statement) {
		String tableName = form.getTableName();
		List<Question> columns = form.getQuestions();
		String sql = null;	
		List <GeometryColumn> geoms = new ArrayList<GeometryColumn> ();

		/*
		 * Attempt to create the table, ignore any exception as the table may already be created
		 * TODO implement as plpgsql with quote_ident() and quote_literal() protection
		 */
		if(columns.size() > 0) {
			sql = "CREATE TABLE " + tableName + " (" +
				"prikey SERIAL PRIMARY KEY, " +
				"parkey int ";
	
			/*
			 * Create default columns
			 * only add _user and _version, _complete, _modified to the top level form
			 */
			sql += ", _bad boolean DEFAULT FALSE, _bad_reason text";
			if(!form.hasParent()) {
				sql += ", _user text, _version text, _complete boolean default true, _modified boolean default false";
			}
							
			for(Question q : columns) {
				
				boolean hasExternalOptions = GeneralUtilityMethods.isAppearanceExternalFile(q.getAppearance());
				
				System.out.println("Adding question: " + q.getName() + " : " + q.getType());
				
				// Ignore questions with no source, these can only be dummy questions that indicate the position of a subform
				if(q.getSource() != null) {
					
					// Set column type for postgres
					String colType = q.getType();
					if(colType.equals("string")) {
						colType = "text";
					} else if(colType.equals("decimal")) {
						colType = "real";
					} else if(colType.equals("select1")) {
						colType = "text";
					} else if(colType.equals("barcode")) {
						colType = "text";
					} else if(colType.equals("geopoint")) {
						
						// Add geometry columns after the table is created using AddGeometryColumn()
						GeometryColumn gc = new GeometryColumn(tableName, q.getColumnName(), "POINT");
						geoms.add(gc);
						continue;
					
					} else if(colType.equals("geopolygon") || colType.equals("geoshape")) {
					
						// remove the automatically generated string _parentquestion from the question name
						String qName = q.getColumnName();
						int idx = qName.lastIndexOf("_parentquestion");
						if(idx > 0) {
							qName = qName.substring(0, idx);
						}
						GeometryColumn gc = new GeometryColumn(tableName, qName, "POLYGON");
						geoms.add(gc);
						continue;
					
					} else if(colType.equals("geolinestring") || colType.equals("geotrace")) {
						
						String qName = q.getColumnName();
						int idx = qName.lastIndexOf("_parentquestion");
						if(idx > 0) {
							qName = qName.substring(0, idx);
						}
						GeometryColumn gc = new GeometryColumn(tableName, qName, "LINESTRING");
						geoms.add(gc);
						continue;
					
					} else if(colType.equals("dateTime")) {
						colType = "timestamp with time zone";					
					} else if(colType.equals("audio") || colType.equals("image") || 
							colType.equals("video")) {
						colType = "text";					
					}
					
					if(colType.equals("select")) {
						// Create a column for each option
						// Values in this column can only be '0' or '1', not using boolean as it may be easier for analysis with an integer
						Collection<Option> options = q.getValidChoices();
						if(options != null) {
							List<Option> optionList = new ArrayList <Option> (options);
							UtilityMethods.sortOptions(optionList);	
							for(Option option : optionList) {
								
								// Create if its an external choice and this question uses external choices
								//  or its not an external choice and this question does not use external choices
								if(hasExternalOptions && option.getExternalFile() || !hasExternalOptions && !option.getExternalFile()) {
									String name = q.getColumnName() + "__" + option.getColumnName();
									sql += ", " + name + " integer";
								}
							}
						} else {
							System.out.println("Warning: No Options for Select:" + q.getName());
						}
					} else {
						sql += ", " + q.getColumnName() + " " + colType;
					}
				} else {
					System.out.println("Info: Ignoring question with no source:" + q.getName());
				}
			}
			sql += ");";
			
			try {
				System.out.println("Sql statement: " + sql);
				statement.execute(sql);
				// Add geometry columns
				for(GeometryColumn gc : geoms) {
					String gSql = "SELECT AddGeometryColumn('" + gc.tableName + 
						"', '" + gc.columnName + "', " + 
						gc.srid + ", '" + gc.type + "', " + gc.dimension + ");";
					System.out.println("Sql statement: " + gSql);
					statement.execute(gSql);
				}
			} catch (SQLException e) {
				System.out.println(e.getMessage());
			}
			
		}

	}
	
	/*
	 * Check for duplicates specified using a column named instanceid or _instanceid
	 * Return true if this instance has already been uploaded
	 */
	private ArrayList<Integer> checkDuplicate(Statement statement, String tableName, String uuid) {
		
		ArrayList<Integer> duplicateKeys = new ArrayList<Integer> ();
		
		uuid = uuid.replace("'", "''");	// Escape apostrophes
		System.out.println("checkDuplicates: " + tableName + " : " + uuid);
		
		try {

			String colTest1 = "select column_name from information_schema.columns " +
					"where table_name = '" + tableName + "' and column_name = '_instanceid'";
			String sql1 = "select prikey from " + tableName + " where _instanceid = '" + uuid + "' " +
					"order by prikey asc;";
			
			// Check for duplicates with the old _instanceid
			ResultSet res = statement.executeQuery(colTest1);
			if(res.next()) {
				// Has _instanceid
				System.out.println("Has _instanceid");
				res = statement.executeQuery(sql1);
				while(res.next()) {
					duplicateKeys.add(res.getInt(1));
				}
			}
			

			String colTest2 = "select column_name from information_schema.columns " +
					"where table_name = '" + tableName + "' and column_name = 'instanceid'";
			String sql2 = "select prikey from " + tableName + " where instanceid = '" + uuid + "' " +
					"order by prikey asc;";
			
			// Check for duplicates with the new instanceid
			res = statement.executeQuery(colTest2);
			if(res.next()) {
				// Has instanceid
				System.out.println("Has instanceid");
				res = statement.executeQuery(sql2);
				while(res.next()) {
					duplicateKeys.add(res.getInt(1));
				}
			}

			
			if(duplicateKeys.size() > 0) {
				System.out.println("Submission has " + duplicateKeys.size() + " duplicates for uuid: " + uuid);
			} 
			
		} catch (SQLException e) {
			System.out.println(e.getMessage());
		}
		
		return duplicateKeys;
	}
	
	
	/*
	 * Apply changes to results table due to changes in the form
	 * Results tables will have to be updated if:
	 *   1.  A new question is added, then either
	 *       - a) Add the new question column to the table that the question is in (that is one form maps to 1 table)
	 *       - b) For a "select" question, add all of the choice columns to the form's table
	 *   2. A new choice is added to a choice list for a select multiple question
	 *   	- Add the new column to all the questions that reference the choice list
	 *       
	 */
	private class QuestionDetails {
		String columnName;
		boolean hasExternalOptions;
		String type;
		String table;
	}
	private class TableUpdateStatus {
		String msg;
		boolean tableAltered;
	}
	
	private void applyTableChanges(Connection connectionSD, Connection cResults, int sId) throws Exception {
		
		String sqlGet = "select c_id, changes "
				+ "from survey_change "
				+ "where apply_results = 'true' "
				+ "and s_id = ? "
				+ "order by c_id asc";
		
		String sqlGetListQuestions = "select q.q_id from question q, listname l " +
				" where q.l_id = l.l_id " +
				" and l.s_id = ? " +
				" and l.l_id = ? " +
				" and q.qtype = 'select'";
		
		String sqlGetOptions = "select column_name, externalfile from option where l_id = ? order by seq asc;";
		String sqlGetAnOption = "select column_name, externalfile from option where l_id = ? and ovalue = ?;";
		
		PreparedStatement pstmtGet = null;
		PreparedStatement pstmtGetListQuestions = null;
		PreparedStatement pstmtGetOptions = null;
		PreparedStatement pstmtGetAnOption = null;
		
		Gson gson =  new GsonBuilder().setDateFormat("yyyy-MM-dd").create();
		
		System.out.println("######## Apply table changes");
		try {
			
			pstmtGet = connectionSD.prepareStatement(sqlGet);
			pstmtGetListQuestions = connectionSD.prepareStatement(sqlGetListQuestions);
			pstmtGetOptions = connectionSD.prepareStatement(sqlGetOptions);
			pstmtGetAnOption = connectionSD.prepareStatement(sqlGetAnOption);
			
			pstmtGet.setInt(1, sId);
			System.out.println("SQL: " + pstmtGet.toString());
			
			ResultSet rs = pstmtGet.executeQuery();
			while(rs.next()) {
				int cId = rs.getInt(1);
				String ciJson = rs.getString(2);
				System.out.println("Apply table change: " + ciJson);
				ChangeItem ci = gson.fromJson(ciJson, ChangeItem.class);
				
				if(ci.action.equals("add") || ci.action.equals("external option")) {		// Table is only altered for new question or new select multiple options
														
					ArrayList<String> columns = new ArrayList<String> ();	// Column names in results table
					int l_id = 0;											// List ID
					TableUpdateStatus status = null;
					
					if(ci.option != null) {
						
						/*
						 * Apply this option to every question that references the option list
						 */
						int listId = ci.option.l_id;
						if(listId == 0) {
							listId = GeneralUtilityMethods.getListId(connectionSD, sId, ci.option.optionList);
						}
						String optionColumnName = null;
						boolean externalFile = false;
						
						// Get the option details
						pstmtGetAnOption.setInt(1, listId);
						pstmtGetAnOption.setString(2, ci.option.value);
						
						System.out.println("Get option details: " + pstmtGetAnOption);
						ResultSet rsOption = pstmtGetAnOption.executeQuery();
						if(rsOption.next()) {
							optionColumnName = rsOption.getString(1);
							externalFile = rsOption.getBoolean(2);
						}
						
						// Get the questions that use this option list
						pstmtGetListQuestions.setInt(1, sId);
						pstmtGetListQuestions.setInt(2, listId);
						
						System.out.println("Get list of questions that refer to an option: " + pstmtGetListQuestions);
						ResultSet rsQuestions = pstmtGetListQuestions.executeQuery();
						
						while(rsQuestions.next()) {
							// Get the question details
							int qId = rsQuestions.getInt(1);
							QuestionDetails qd = getQuestionDetails(connectionSD, qId);
							
							if(qd != null) {
								if(qd.hasExternalOptions && externalFile || !qd.hasExternalOptions && !externalFile) {
									status = alterColumn(cResults, qd.table, "integer", qd.columnName + "__" + optionColumnName);
								}
							}

						}
						
					
					} else if(ci.question != null ) {
						// Don't rely on any parameters in the change item, they may have been changed again after the question was added
						int qId = GeneralUtilityMethods.getQuestionId(connectionSD, ci.question.fId, ci.question.name);
						
						QuestionDetails qd = getQuestionDetails(connectionSD, qId);

						columns.add(qd.columnName);		// Usually this is the case unless the question is a select multiple
						
						if(qd.type.equals("string")) {
							qd.type = "text";
						} else if(qd.type.equals("dateTime")) {
							qd.type = "timestamp with time zone";					
						} else if(qd.type.equals("audio") || qd.type.equals("image") || qd.type.equals("video")) {
							qd.type = "text";					
						} else if(qd.type.equals("decimal")) {
							qd.type = "real";
						} else if(qd.type.equals("barcode")) {
							qd.type = "text";
						} else if(qd.type.equals("note")) {
							qd.type = "text";
						} else if(qd.type.equals("select1")) {
							qd.type = "text";
						} else if (qd.type.equals("select")) {
							qd.type = "integer";
							
							columns.clear();
							pstmtGetOptions.setInt(1, l_id);
							
							System.out.println("Get options to add: "+ pstmtGetOptions.toString());
							ResultSet rsOptions = pstmtGetOptions.executeQuery();
							while(rsOptions.next()) {			
								// Create if its an external choice and this question uses external choices
								//  or its not an external choice and this question does not use external choices
								String o_col_name = rsOptions.getString(1);
								boolean externalFile = rsOptions.getBoolean(2);

								if(qd.hasExternalOptions && externalFile || !qd.hasExternalOptions && !externalFile) {
									String column =  qd.columnName + "__" + o_col_name;
									columns.add(column);
								}
							} 
						}
						
						// Apply each column
						for(String col : columns) {
							status = alterColumn(cResults, qd.table, qd.type, col);
						}
						
					}
					
					// Record the application of the change and the status
					String msg = status != null ? status.msg : "";
					boolean tableAltered = status != null ? status.tableAltered : false;
					markChangeApplied(connectionSD, cId, tableAltered, msg);

						
	
				} else {
					// Record that this change has been processed
					markChangeApplied(connectionSD, cId, false, "");
				}

			}
			
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		} finally {
			try {if (pstmtGet != null) {pstmtGet.close();}} catch (Exception e) {}
			try {if (pstmtGetOptions != null) {pstmtGetOptions.close();}} catch (Exception e) {}
			try {if (pstmtGetAnOption != null) {pstmtGetAnOption.close();}} catch (Exception e) {}
			try {if (pstmtGetListQuestions != null) {pstmtGetListQuestions.close();}} catch (Exception e) {}
		}
		
	}
	
	/*
	 * Alter the table
	 */
	private TableUpdateStatus alterColumn(Connection cResults, String table, String type, String column) {
		
		PreparedStatement pstmtAlterTable = null;
		PreparedStatement pstmtApplyGeometryChange = null;
		
		TableUpdateStatus status = new TableUpdateStatus();
		status.tableAltered = true;
		status.msg = "";
		
		try {
			if(type.equals("geopoint") || type.equals("geotrace") || type.equals("geoshape")) {
				
				String geoType = null;
				
				if(type.equals("geopoint")) {
					geoType = "POINT";
				} else if (type.equals("geotrace")) {
					geoType = "LINESTRING";
				} else if (type.equals("geoshape")) {
					geoType = "POLYGON";
				}
				String gSql = "SELECT AddGeometryColumn('" + table + 
						"', 'the_geom', 4326, '" + geoType + "', 2);";
					System.out.println("Sql statement: " + gSql);
					
					pstmtApplyGeometryChange = cResults.prepareStatement(gSql);
					pstmtApplyGeometryChange.executeQuery();
					
			} else {
				
				String sqlAlterTable = "alter table " + table + " add column " + column + " " + type + ";";
				pstmtAlterTable = cResults.prepareStatement(sqlAlterTable);
				System.out.println("SQL: " + pstmtAlterTable.toString());
			
				System.out.println("Pre-alter table: Auto commit for results is: " + cResults.getAutoCommit());
				pstmtAlterTable.executeUpdate();
				
				// Commit this change to the database
				try {
					cResults.commit();
				} catch(Exception ex) {
					
				}
			} 
		} catch (Exception e) {
			// Report but otherwise ignore any errors
			System.out.println("Error altering table -- continuing: " + e.getMessage());
			
			// Rollback this change
			try {
				cResults.rollback();
			} catch(Exception ex) {
				
			}
			
			// Only record the update as failed if the problem was not due to the column already existing
			status.msg = e.getMessage();
			if(status.msg == null || !status.msg.contains("already exists")) {
				status.tableAltered = false;
			}
		} finally {
			try {if (pstmtAlterTable != null) {pstmtAlterTable.close();}} catch (Exception e) {}
			try {if (pstmtApplyGeometryChange != null) {pstmtApplyGeometryChange.close();}} catch (Exception e) {}
		}
		return status;
	}
	
	private QuestionDetails getQuestionDetails(Connection sd, int qId) throws Exception {
		
		QuestionDetails qd = new QuestionDetails();
		PreparedStatement pstmt = null;;
		
		String sqlGetQuestionDetails = "select q.column_name, q.appearance, q.qtype, f.table_name "
				+ "from question q, form f "
				+ "where q.f_id = f.f_id "
				+ "and q_id = ?;";
		
		try {
			pstmt = sd.prepareStatement(sqlGetQuestionDetails);
			
			pstmt.setInt(1, qId);
			
			System.out.println("Get question details: " + pstmt.toString());
			ResultSet rsDetails = pstmt.executeQuery();
			if(rsDetails.next()) {
				qd.columnName = rsDetails.getString(1);
				qd.hasExternalOptions = GeneralUtilityMethods.isAppearanceExternalFile(rsDetails.getString(2));
				qd.type = rsDetails.getString(3);
				qd.table = rsDetails.getString(4);
			} else {
				throw new Exception("Can't find question details: " + qId);
			}
		} catch (SQLException e) {
			e.printStackTrace();
			throw e;
		} finally {
			try {if (pstmt != null) {pstmt.close();}} catch (Exception e) {}
		}
		
		return qd;
	}
	/*
	 * Mark all the questions and options in the survey as published
	 */
	private void markPublished(Connection sd, int sId) throws SQLException {
		
		
		String sqlSetPublished = "update question set published = 'true' where f_id in (select f_id from form where s_id = ?);";
		String sqlSetOptionsPublished = "update option set published = 'true' "
				+ "where l_id in (select l_id from question q, form f where f.s_id = ? and q.f_id = f.f_id);";

		PreparedStatement pstmtSetPublished = null;
		try {
		
			pstmtSetPublished = sd.prepareStatement(sqlSetPublished);
			pstmtSetPublished.setInt(1, sId);
			
			System.out.println("Mark published: " + pstmtSetPublished.toString());
			pstmtSetPublished.executeUpdate();
			
			pstmtSetPublished.close();
			pstmtSetPublished = sd.prepareStatement(sqlSetOptionsPublished);
			pstmtSetPublished.setInt(1, sId);
			
			System.out.println("Mark published: " + pstmtSetPublished.toString());
			pstmtSetPublished.executeUpdate();
			
		} catch (SQLException e) {
			e.printStackTrace();
			throw e;
		} finally {
			try {if (pstmtSetPublished != null) {pstmtSetPublished.close();}} catch (Exception e) {}
		}
		
	}
	
	private void markChangeApplied(Connection sd, int cId, boolean success, String msg) throws SQLException {
		
		String sqlUpdateChange = "update survey_change "
				+ "set apply_results = 'false', "
				+ "success = ?, "
				+ "msg = ? "
				+ "where c_id = ? ";
		
		PreparedStatement pstmtUpdateChange = null;
		try {
			pstmtUpdateChange = sd.prepareStatement(sqlUpdateChange);
			
			pstmtUpdateChange.setBoolean(1, success);
			pstmtUpdateChange.setString(2, msg);
			pstmtUpdateChange.setInt(3, cId);
			pstmtUpdateChange.executeUpdate();
			
		}catch (SQLException e) {
			e.printStackTrace();
			throw e;
		} finally {
			try {if (pstmtUpdateChange != null) {pstmtUpdateChange.close();}} catch (Exception e) {}
		}
		
	}
	
	private void markAllChangesApplied(Connection sd, int sId) throws SQLException {
		
		String sqlUpdateChange = "update survey_change "
				+ "set apply_results = 'false', "
				+ "success = 'true' "
				+ "where s_id = ? ";
		
		PreparedStatement pstmtUpdateChange = null;
		try {
			pstmtUpdateChange = sd.prepareStatement(sqlUpdateChange);
			
			pstmtUpdateChange.setInt(1, sId);
			pstmtUpdateChange.executeUpdate();
			
		}catch (SQLException e) {
			e.printStackTrace();
			throw e;
		} finally {
			try {if (pstmtUpdateChange != null) {pstmtUpdateChange.close();}} catch (Exception e) {}
		}
		
	}

}
