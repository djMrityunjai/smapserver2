package org.smap.sdal.managers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

import org.smap.notifications.interfaces.EmitDeviceNotification;
import org.smap.sdal.Utilities.GeneralUtilityMethods;
import org.smap.sdal.Utilities.UtilityMethodsEmail;
import org.smap.sdal.model.EmailServer;
import org.smap.sdal.model.SurveyMessage;
import org.smap.sdal.model.Organisation;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/*****************************************************************************
 * 
 * This file is part of SMAP.
 * 
 * SMAP is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * 
 * SMAP is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * SMAP. If not, see <http://www.gnu.org/licenses/>.
 * 
 ******************************************************************************/

/*
 * Manage the table that stores details on the forwarding of data onto other
 * systems
 */
public class MessagingManager {

	private static Logger log = Logger.getLogger(MessagingManager.class.getName());

	LogManager lm = new LogManager(); // Application log

	/*
	 * Create a message resulting from a change to a form
	 */
	public void surveyChange(Connection sd, int sId) throws SQLException {
		Gson gson = new GsonBuilder().setDateFormat("yyyy-MM-dd").create();
		String data = gson.toJson(new SurveyMessage(sId));		
		int oId = GeneralUtilityMethods.getOrganisationIdForSurvey(sd, sId);		
		createMessage(sd, oId, "survey", null, data);
	}
	
	/*
	 * Create a new message
	 */
	public void createMessage(Connection sd, int oId, String topic, String msg, String data) throws SQLException {
		
		String sqlMsg = "insert into message" + "(o_id, topic, description, data, outbound, created_time) "
				+ "values(?, ?, ?, ?, 'true', now())";
		PreparedStatement pstmtMsg = null;
		
		try {
			pstmtMsg = sd.prepareStatement(sqlMsg);
			pstmtMsg.setInt(1, oId);
			pstmtMsg.setString(2, topic);
			pstmtMsg.setString(3, msg);
			pstmtMsg.setString(4, data);
			log.info("Add message: " + pstmtMsg.toString());
			pstmtMsg.executeUpdate();
		} finally {

			try {if (pstmtMsg != null) {	pstmtMsg.close();}} catch (SQLException e) {}

		}
	}
	
	/*
	 * Apply any outbound messages
	 */
	public void applyOutbound(Connection sd, String serverName) {

		ResultSet rs = null;
		PreparedStatement pstmtGetMessages = null;
		PreparedStatement pstmtConfirm = null;
		
		HashMap<Integer, SurveyMessage> changedSurveys = new HashMap<> ();
		HashMap<String, SurveyMessage> usersImpacted =   new HashMap<> ();

		String sqlGetMessages = "select id, "
				+ "o_id, "
				+ "topic, "
				+ "description, "
				+ "data "
				+ "from message "
				+ "where outbound "
				+ "and processed_time is null";

		String sqlConfirm = "update message set processed_time = now(), status = ? where id = ?; ";

		try {

			EmitDeviceNotification emitDevice = new EmitDeviceNotification();
			pstmtGetMessages = sd.prepareStatement(sqlGetMessages);
			pstmtConfirm = sd.prepareStatement(sqlConfirm);

			rs = pstmtGetMessages.executeQuery();
			while (rs.next()) {

				int id = rs.getInt(1);
				int o_id = rs.getInt(2);
				String topic = rs.getString(3);
				String description = rs.getString(4);
				String data = rs.getString(5);
				
				// Localisation
				Organisation organisation = UtilityMethodsEmail.getOrganisationDefaults(sd, o_id);
				Locale locale = new Locale(organisation.locale);
				ResourceBundle localisation = ResourceBundle.getBundle("org.smap.sdal.resources.SmapResources", locale);
				
				log.info("++++++ Message: " + topic + " " + description + " : " + data );

				String status = "Success";
				
				/*
				 * Record that the message is being processed
				 * After this point it will not be processed again even if it fails unless there is manual intervention
				 */
				pstmtConfirm.setString(1, "Sending");
				pstmtConfirm.setInt(2, id);
				log.info(pstmtConfirm.toString());
				pstmtConfirm.executeUpdate();
				
				if(topic.equals("survey")) {
					Gson gson=  new GsonBuilder().disableHtmlEscaping().setDateFormat("yyyy-MM-dd HH:mm:ss").create();
					SurveyMessage sm = gson.fromJson(data, SurveyMessage.class);
					if(sm != null) {
						System.out.println("xxxxxxxxxxxxxxxxxxxx Processing: " + sm.id);
					} else {
						System.out.println("Error: null survey message");
					}
					
					changedSurveys.put(sm.id, sm);
					
				} else {
					// Assume a direct email process immediately

					EmailServer emailServer = UtilityMethodsEmail.getSmtpHost(sd, null, null);
					if (isValidEmail(topic) && 
							emailServer.smtpHost != null && emailServer.smtpHost.trim().length() > 0) {
	
						// Set the subject
						String subject = "";
						String from = "";
	
						subject += localisation.getString("c_message");
	
						try {
							EmailManager em = new EmailManager();
	
							em.sendEmail(topic, null, "notify", subject, description, from, null, null, null, null, null,
									null, organisation.getAdminEmail(), emailServer, "https", serverName, localisation);
						} catch (Exception e) {
							status = "Error";
						}
	
					} else {
						log.log(Level.SEVERE, "Error: Attempt to do email notification but email server not set");
						status = "Error: email server not enabled";
					}
					
				}
				// Set the final status
				pstmtConfirm.setString(1, status);
				pstmtConfirm.setInt(2, id);
				log.info(pstmtConfirm.toString());
				pstmtConfirm.executeUpdate();

			}
			
			/*
			 * Device notifications have been accumulated to an array so that duplicates can be eliminated
			 * Process these now
			 */
			for(Integer sId : changedSurveys.keySet()) {
				ArrayList<String> users = getSurveyUsers(sd, sId);
				for(String user : users) {
					System.out.println("Need to notify:  " + user);
				}
			}
			
			

		} catch (Exception e) {
			log.log(Level.SEVERE, "Error", e);
		} finally {
			try {
				if (pstmtGetMessages != null) {
					pstmtGetMessages.close();
				}
			} catch (Exception e) {
			}
			try {
				if (pstmtConfirm != null) {
					pstmtConfirm.close();
				}
			} catch (Exception e) {
			}
		}

	}

	/*
	 * Validate an email
	 */
	public boolean isValidEmail(String email) {
		boolean isValid = true;
		try {
			InternetAddress emailAddr = new InternetAddress(email);
			emailAddr.validate();
		} catch (AddressException ex) {
			isValid = false;
		}
		return isValid;
	}
	
	/*
	 * Get users of a survey
	 */
	ArrayList<String> getSurveyUsers(Connection sd, int sId) throws SQLException {
		
		ArrayList<String> users = new ArrayList<String> ();
		String sql = "select u.ident "
				+ "from users u, user_project up, survey s "
				+ "where u.id = up.u_id "
				+ "and s.p_id = up.p_id "
				+ "and s.s_id = ? and not temporary";

		PreparedStatement pstmt = null;
		
		try {
			pstmt = sd.prepareStatement(sql);
			pstmt.setInt(1, sId);
			log.info("Get survey users: " + pstmt.toString());
			ResultSet rs = pstmt.executeQuery();
			while(rs.next()) {
				users.add(rs.getString(1));
			}
		} finally {

			try {if (pstmt != null) {	pstmt.close();}} catch (SQLException e) {}

		}
		
		return users;
	}
}
