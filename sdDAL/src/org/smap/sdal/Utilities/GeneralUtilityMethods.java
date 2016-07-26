package org.smap.sdal.Utilities;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Response;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.smap.sdal.model.ChangeItem;
import org.smap.sdal.model.Column;
import org.smap.sdal.model.Form;
import org.smap.sdal.model.Language;
import org.smap.sdal.model.ManifestInfo;
import org.smap.sdal.model.Option;
import org.smap.sdal.model.PropertyChange;
import org.smap.sdal.model.Result;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;


public class GeneralUtilityMethods {
	
	private static Logger log =
			 Logger.getLogger(GeneralUtilityMethods.class.getName());

	private static int LENGTH_QUESTION_NAME = 45;   // 63 max size of postgresql column names. Allow 10 chars for options + 2 chars for option separator
	private static int LENGTH_QUESTION_RAND = 3;
	private static int LENGTH_OPTION_NAME = 16;  
	private static int LENGTH_OPTION_RAND = 3;
	
	private static String [] reservedSQL = new String [] {
		"all",
		"analyse",
		"analyze",
		"and",
		"any",
		"array",
		"as",
		"asc",
		"assignment",
		"asymmetric",
		"authorization",
		"between",
		"binary",
		"both",
		"case",
		"cast",
		"check",
		"collate",
		"column",
		"constraint",
		"create",
		"cross",
		"current_date",
		"current_role",
		"current_time",
		"current_timestamp",
		"current_user",
		"default",
		"deferrable",
		"desc",
		"distinct",
		"do",
		"else",
		"end",
		"except",
		"false",
		"for",
		"foreign",
		"freeze",
		"from",
		"full",
		"grant",
		"group",
		"having",
		"ilike",
		"in",
		"initially",
		"inner",
		"intersect",
		"into",
		"is",
		"isnull",
		"join",
		"leading",
		"left",
		"like",
		"limit",
		"localtime",
		"localtimestamp",
		"natural",
		"new",
		"not",
		"notnull",
		"null",
		"off",
		"offset",
		"old",
		"on",
		"only",
		"or",
		"order",
		"outer",
		"overlaps",
		"placing",
		"primary",
		"references",
		"right",
		"select",
		"session_user",
		"similar",
		"some",
		"symmetric",
		"table",
		"then",
		"to",
		"trailing",
		"true",
		"union",
		"unique",
		"user",
		"using",
		"verbose",
		"when",
		"where"
	};
	
	/*
	 * Remove any characters from the name that will prevent it being used as a database column name
	 */
	static public String cleanName(String in, boolean isQuestion, boolean removeSqlReserved) {
		
		String out = in.trim().toLowerCase();

		out = out.replace(" ", "");	// Remove spaces
		out = out.replaceAll("[\\.\\[\\\\^\\$\\|\\?\\*\\+\\(\\)\\]\"\';,:!@#&%/{}<>-]", "x");	// Remove special characters ;
	
		/*
		 * Rename fields that are the same as postgres / sql reserved words
		 */
		if(removeSqlReserved) {
			for(int i = 0; i < reservedSQL.length; i++) {
				if(out.equals(reservedSQL[i])) {
					out = "__" + out;
					break;
				}
			}
		}
		
		// If the name exceeds the max length then truncate to max size and add random characters to the end of the name
		int maxlength = isQuestion ? (LENGTH_QUESTION_NAME - LENGTH_QUESTION_RAND) : (LENGTH_OPTION_NAME - LENGTH_OPTION_RAND);
		int randLength = isQuestion ? LENGTH_QUESTION_RAND : LENGTH_OPTION_RAND;
		
		if(out.length() >= maxlength) {
			out = out.substring(0, maxlength);
			
			String rand  = String.valueOf(UUID.randomUUID());
			rand = rand.substring(0, randLength);
			
			out += rand;
		}
		
		return out;
	}
	
	
	
	/*
	 * Escape characters reserved for HTML
	 */
	static public String esc(String in) {
		String out = in;
		if(out != null) {
			out = out.replace("&", "&amp;");
			out = out.replace("<", "&lt;");
			out = out.replace(">", "&gt;");
		}
		return out;
	}
	
	/*
	 * Unescape characters reserved for HTML
	 */
	static public String unesc(String in) {
		String out = in;
		if(out != null) {
			out = out.replace("&amp;", "&");
			out = out.replace("&lt;", "<");
			out = out.replace("&gt;", ">");
		}
		return out;
	}
	
	/*
	 * Get Base Path
	 */
	static public String getBasePath(HttpServletRequest request) {
		String basePath = request.getServletContext().getInitParameter("au.com.smap.files");
		if(basePath == null) {
			basePath = "/smap";
		} else if(basePath.equals("/ebs1")) {		// Support for legacy apache virtual hosts
			basePath = "/ebs1/servers/" + request.getServerName().toLowerCase();
		}
		return basePath;
	}
	
	/*
	 * Get the URL prefix for media
	 */
	static public String getUrlPrefix(HttpServletRequest request) {
		return request.getScheme() + "://" + request.getServerName() + "/";
	}
	
	/*
	 * Throw a 404 exception if this is not a business server
	 */
	static public void assertBusinessServer(String host) {
		log.info("Business Server check: " + host);
		
		if(!isBusinessServer(host)) {
			log.info("Business Server check failed: " + host);
			throw new AuthorisationException();
		}
		
	}
	
	static public boolean isBusinessServer(String host) {
		
		boolean businessServer = true;
		
		if(!host.endsWith("zarkman.com") &&
				!host.equals("localhost") &&
				!host.startsWith("10.0") &&
				!host.endsWith("reachnettechnologies.com") &&
				!host.endsWith("datacollect.icanreach.com") &&
				!host.endsWith(".kontrolid.com") &&
				!host.endsWith(".smap.com.au")) {
			businessServer = false;;
		}
		return businessServer;
	}
	
	/*
	 * Throw a 404 exception if this is not a self registration server
	 */
	static public void assertSelfRegistrationServer(String host) {
		log.info("Self registration check: " + host);
		
		if(!host.equals("sg.smap.com.au") &&
				!host.equals("localhost") &&
				!host.endsWith("reachnettechnologies.com") &&
				!host.endsWith("datacollect.icanreach.com") &&
				!host.equals("app.kontrolid.com")) {
			
			log.info("Self registration check failed: " + host);
			throw new AuthorisationException();
		}
		
	}
	
	/*
	 * Rename template files
	 */
	static public void renameTemplateFiles(String oldName, String newName, String basePath, int oldProjectId, int newProjectId ) throws IOException {
		
		String oldFileName = convertDisplayNameToFileName(oldName);
		String newFileName = convertDisplayNameToFileName(newName);
			
		String fromDirectory = basePath + "/templates/" + oldProjectId;
		String toDirectory = basePath + "/templates/" + newProjectId;
			
		log.info("Renaming files from " + fromDirectory + "/" + oldFileName + " to " + toDirectory + "/" + newFileName);
		File dir = new File(fromDirectory);
		FileFilter fileFilter = new WildcardFileFilter(oldFileName + ".*");
		File[] files = dir.listFiles(fileFilter);
		
		if(files != null) {
			if(files.length > 0) {
				moveFiles(files, toDirectory, newFileName);  
			} else {
				
				// Try the old /templates/xls location for files
				fromDirectory = basePath + "/templates/XLS";
				dir = new File(fromDirectory);
				files = dir.listFiles(fileFilter);
				moveFiles(files, toDirectory, newFileName); 
				
				// try the /templates location
				fromDirectory = basePath + "/templates";
				dir = new File(fromDirectory);
				files = dir.listFiles(fileFilter);
				moveFiles(files, toDirectory, newFileName); 
				
			}
		}
		 
	}
	
	/*
	 * Move an array of files to a new location
	 */
	static void moveFiles(File[] files, String toDirectory, String newFileName)  {
		if(files != null) {	// Can be null if the directory did not exist
			for (int i = 0; i < files.length; i++) {
			   log.info("renaming file: " + files[i]);
			   String filename = files[i].getName();
			   String ext = filename.substring(filename.lastIndexOf('.'));
			   String newPath = toDirectory + "/" + newFileName + ext;
			   try {
				   FileUtils.moveFile(files[i], new File(newPath));
				   log.info("Moved " + files[i] + " to " + newPath );
			   } catch (IOException e) {
				   log.info("Error moving " + files[i] + " to " + newPath + ", message: " + e.getMessage() );
				   
			   }
			}
		}
	}
	
	/*
	 * Delete template files
	 */
	static public void deleteTemplateFiles(String name, String basePath, int projectId ) throws IOException {
		
		String fileName = convertDisplayNameToFileName(name);
		
		
		String directory = basePath + "/templates/" + projectId;
		log.info("Deleting files in " + directory + " with stem: " + fileName);
		File dir = new File(directory);
		FileFilter fileFilter = new WildcardFileFilter(fileName + ".*");
		File[] files = dir.listFiles(fileFilter);
		 for (int i = 0; i < files.length; i++) {
		   log.info("deleting file: " + files[i]);
		   files[i].delete();
		 }
	}
	
	/*
	 * Delete a directory
	 */
	static public void deleteDirectory(String directory) {
		
		File dir = new File(directory);
		
		File[] files = dir.listFiles();
		if(files != null) {
			for (int i = 0; i < files.length; i++) {
				log.info("deleting file: " + files[i]);
				if(files[i].isDirectory()) {
					deleteDirectory(files[i].getAbsolutePath());
				} else {
					files[i].delete();
				}		
			}
		}
		log.info("Deleting directory " + directory);
		dir.delete();
	}
	
	/*
	 * Get the PDF Template File
	 */
	static public File getPdfTemplate(String basePath, String displayName, int pId) {
		
		String templateName = basePath + "/templates/" + pId + "/" + 
				convertDisplayNameToFileName(displayName) +
				"_template.pdf";
		
		log.info("Attempt to get a pdf template with name: " + templateName);
		File templateFile = new File(templateName);
		
		return templateFile;
	}
	
	/*
	 * convert display name to file name
	 */
	static public String convertDisplayNameToFileName(String name) {
		// Remove special characters from the display name.  Use the display name rather than the source name as old survey files had spaces replaced by "_" wheras source name had the space removed
	    String specRegex = "[\\.\\[\\\\^\\$\\|\\?\\*\\+\\(\\)\\]\"\';,:!@#&%/{}<>-]";
		String file_name = name.replaceAll(specRegex, "");	
		file_name = file_name.replaceAll(" ", "_");
		file_name = file_name.replaceAll("\\P{Print}", "_");	// remove all non printable (non ascii) characters. 
		
		return file_name;
	}
	
	/*
	 * 
	 */
	static public String createAttachments(String srcName, File srcPathFile, String basePath, String surveyName) {
		
		log.info("Create attachments");
		
		String value = null;
		String srcExt = "";
		
		int idx = srcName.lastIndexOf('.');
		if(idx > 0) {
			srcExt = srcName.substring(idx+1);
		}
		
		String dstName = String.valueOf(UUID.randomUUID());
		String dstDir = basePath + "/attachments/" + surveyName;
		String dstThumbsPath = basePath + "/attachments/" + surveyName + "/thumbs";
		String dstFlvPath = basePath + "/attachments/" + surveyName + "/flv";
		File dstPathFile = new File(dstDir + "/" + dstName + "." + srcExt);
		File dstDirFile = new File(dstDir);
		File dstThumbsFile = new File(dstThumbsPath);
		File dstFlvFile = new File(dstFlvPath);

		String contentType = org.smap.sdal.Utilities.UtilityMethodsEmail.getContentType(srcName);

		try {
			log.info("Processing attachment: " + srcPathFile.getAbsolutePath() + " as " + dstPathFile);
			FileUtils.forceMkdir(dstDirFile);
			FileUtils.forceMkdir(dstThumbsFile);
			FileUtils.forceMkdir(dstFlvFile);
			FileUtils.copyFile(srcPathFile, dstPathFile);
			processAttachment(dstName, dstDir, contentType,srcExt);
			
		} catch (IOException e) {
			log.log(Level.SEVERE,"Error", e);
		}
		// Create a URL that references the attachment (but without the hostname or scheme)
		value = "attachments/" + surveyName + "/" + dstName + "." + srcExt;
		
		return value;
	}
	
	/*
	 * Create thumbnails, reformat video files etc
	 */
	private static void processAttachment(String fileName, String destDir, String contentType, String ext) {

    	String cmd = "/usr/bin/smap/processAttachment.sh " + fileName + " " + destDir + " " + contentType +
    			" " + ext +
 				" >> /var/log/subscribers/attachments.log 2>&1";
		log.info("Exec: " + cmd);
		try {

			Process proc = Runtime.getRuntime().exec(new String [] {"/bin/sh", "-c", cmd});
    		
    		int code = proc.waitFor();
    		log.info("Attachment processing finished with status:" + code);
    		if(code != 0) {
    			log.info("Error: Attachment processing failed");
    		}
    		
		} catch (Exception e) {
			e.printStackTrace();
    	}
		
	}
	
	/*
	 * Get the organisation id for the user
	 */
	static public int getOrganisationId(
			Connection sd, 
			String user) throws SQLException {
		
		int o_id = -1;
		
		String sqlGetOrgId = "select o_id " +
				" from users u " +
				" where u.ident = ?;";
		
		PreparedStatement pstmt = null;
		
		try {
		
			pstmt = sd.prepareStatement(sqlGetOrgId);
			pstmt.setString(1, user);
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				o_id = rs.getInt(1);	
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) { pstmt.close();}} catch (SQLException e) {}
		}
		
		return o_id;
	}
	
	/*
	 * Get the organisation name for the organisation id
	 */
	static public String getOrganisationName(
			Connection sd, 
			int o_id) throws SQLException {
		
		
		String sqlGetOrgName = "select o.name, o.company_name " +
				" from organisation o " +
				" where o.id = ?;";
		
		PreparedStatement pstmt = null;
		String name = null;
		
		try {
		
			pstmt = sd.prepareStatement(sqlGetOrgName);
			pstmt.setInt(1, o_id);
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				name = rs.getString(2);	
				if(name == null) {
					name = rs.getString(1);
				}
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) { pstmt.close();}} catch (SQLException e) {}
		}
		
		return name;
	}
	
	/*
	 * Get the user id from the user ident
	 */
	static public int getUserId(
			Connection sd, 
			String user) throws SQLException {
		
		int u_id = -1;
		
		String sqlGetUserId = "select id " +
				" from users u " +
				" where u.ident = ?;";
		
		PreparedStatement pstmt = null;
		
		try {
		
			pstmt = sd.prepareStatement(sqlGetUserId);
			pstmt.setString(1, user);
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				u_id = rs.getInt(1);	
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) { pstmt.close();}} catch (SQLException e) {}
		}
		
		return u_id;
	}
	
	/*
	 * Update the project id in the upload_event table
	 */
	static public void updateUploadEvent(
			Connection sd, 
			int pId,
			int sId) throws SQLException {
	
		
		String updatePId = "update upload_event set p_id = ? where s_id = ?;"; 
		
		PreparedStatement pstmt = null;
		
		try {
		
			pstmt = sd.prepareStatement(updatePId);
			pstmt.setInt(1, pId);
			pstmt.setInt(2, sId);
			pstmt.executeUpdate();	
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) { pstmt.close();}} catch (SQLException e) {}
		}

	}
	
    /*
     * Get Safe Template File Name
     *  Returns safe file names from the display name for the template
     */
	static public String getSafeTemplateName(String targetName) {
		String specRegex = "[\\.\\[\\\\^\\$\\|\\?\\*\\+\\(\\)\\]\"\';,:!@#&%/{}<>-]";
		targetName = targetName.replaceAll(specRegex, "");	
		targetName = targetName.replaceAll(" ", "_");
		// The target name is not shown to users so it doesn't need to support unicode, however pyxform fails if it includes unicode chars
		targetName = targetName.replaceAll("\\P{Print}", "_");	// remove all non printable (non ascii) characters. 
	
		return targetName;
	}
	
	/*
	 * Get the survey ident from the id
	 */
	static public String getSurveyIdent(
			Connection sd, 
			int surveyId) throws SQLException {
		
		 String surveyIdent = null;
		
		String sqlGetSurveyIdent = "select ident " +
				" from survey " +
				" where s_id = ?;";
		
		PreparedStatement pstmt = null;
		
		try {
		
			pstmt = sd.prepareStatement(sqlGetSurveyIdent);
			pstmt.setInt(1, surveyId);
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				surveyIdent = rs.getString(1);	
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) { pstmt.close();}} catch (SQLException e) {}
		}
		
		return surveyIdent;
	}
	
	/*
	 * Get the survey id from the ident
	 */
	static public int getSurveyId(
			Connection sd, 
			String sIdent) throws SQLException {
		
		int sId = 0;
		
		String sql = "select s_id " +
				" from survey " +
				" where ident = ?;";
		
		PreparedStatement pstmt = null;
		
		try {
		
			pstmt = sd.prepareStatement(sql);
			pstmt.setString(1, sIdent);
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				sId = rs.getInt(1);	
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) { pstmt.close();}} catch (SQLException e) {}
		}
		
		return sId;
	}
	
	/*
	 * Get the survey name from the id
	 */
	static public String getSurveyName(
			Connection sd, 
			int surveyId) throws SQLException {
		
		 String surveyName = null;
		
		String sqlGetSurveyName = "select display_name " +
				" from survey " +
				" where s_id = ?;";
		
		PreparedStatement pstmt = null;
		
		try {
		
			pstmt = sd.prepareStatement(sqlGetSurveyName);
			pstmt.setInt(1, surveyId);
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				surveyName = rs.getString(1);	
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) { pstmt.close();}} catch (SQLException e) {}
		}
		
		return surveyName;
	}
	
	/*
	 * Return true if the upload error has already been reported
	 * This function is used to prevent large numbers of duplicate errors beign recorded when 
	 *  submission of bad results is automatically retried
	 */
	public static boolean hasUploadErrorBeenReported(
			Connection sd, 
			String user, 
			String device, 
			String ident, 
			String reason) throws SQLException {
		
		boolean reported = false;
		
		String sqlReport = "select count(*) from upload_event " +
				"where user_name = ? " +
				"and imei = ? " +
				"and ident = ? " +
				"and reason = ?;";
			
		PreparedStatement pstmt = null;
		
		try {
		
			pstmt = sd.prepareStatement(sqlReport);
			pstmt.setString(1, user);
			pstmt.setString(2, device);
			pstmt.setString(3, ident);
			pstmt.setString(4, reason);
			log.info("Has error been reported: " + pstmt.toString());
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				reported = (rs.getInt(1) > 0) ? true : false;	
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) { pstmt.close();}} catch (SQLException e) {}
		}
		
		return reported;
	}
	
	/*
	 * Get the survey project id from the survey id
	 */
	static public int getProjectId(
			Connection sd, 
			int surveyId) throws SQLException {
		
		int p_id = 0;
		
		String sqlGetSurveyIdent = "select p_id " +
				" from survey " +
				" where s_id = ?;";
		
		PreparedStatement pstmt = null;
		
		try {
		
			pstmt = sd.prepareStatement(sqlGetSurveyIdent);
			pstmt.setInt(1, surveyId);
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				p_id = rs.getInt(1);	
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) { pstmt.close();}} catch (SQLException e) {}
		}
		
		return p_id;
	}
	
	/*
	 * Get the survey human readable key using the survey id
	 */
	static public String getHrk(
			Connection sd, 
			int surveyId) throws SQLException {
		
		String hrk = null;
		
		String sql = "select hrk " +
				" from survey " +
				" where s_id = ?;";
		
		PreparedStatement pstmt = null;
		
		try {
		
			pstmt = sd.prepareStatement(sql);
			pstmt.setInt(1, surveyId);
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				hrk = rs.getString(1);	
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) { pstmt.close();}} catch (SQLException e) {}
		}
		
		return hrk;
	}
	
	/*
	 * Get the question id using the form id and question name
	 * Used by the editor to get the question id of a newly created question
	 * 
	 * 
	 */
	static public int getQuestionId(
			Connection sd, 
			int formId,
			int sId,
			int changeQId,
			String qName) throws Exception {
		
		int qId = 0;
		
		String sqlGetQuestionId = "select q_id " +
				" from question " +
				" where f_id = ? " +
				" and qname = ?;";
		
		String sqlGetQuestionIdFromSurvey = "select q_id " +
				" from question " +
				" where qname = ? "
				+ "and f_id in (select f_id from form where s_id = ?); ";
		
		PreparedStatement pstmt = null;
		
		try {
		
			pstmt = sd.prepareStatement(sqlGetQuestionId);
			pstmt.setInt(1, formId);
			pstmt.setString(2, qName);
			log.info("SQL get question id: " + pstmt.toString());
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				qId = rs.getInt(1);	
			} else {
				// Try without the form id, the question may have been moved to a different form
				pstmt.close();
				pstmt = sd.prepareStatement(sqlGetQuestionIdFromSurvey);
				pstmt.setString(1, qName);
				pstmt.setInt(2, sId);
				log.info("Getting question id without the form id: " + pstmt.toString());
				rs = pstmt.executeQuery();
				if(rs.next()) {
					qId = rs.getInt(1);
					log.info("Found qId: " + qId);
				} else {
					throw new Exception("Question not found: " + sId + " : " + formId + " : " + qName);
				}
				
				// If there is more than one question with the same name then use the qId in the change item
				// This will work for existing questions and this question was presumably added from xlsForm
				if(rs.next()) {
					log.info("setting question id to changeQId: " + changeQId);
					qId = changeQId;
				}
			}
			
		} catch(Exception e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) { pstmt.close();}} catch (SQLException e) {}
		}
		
		return qId;
	}
	
	
	/*
	 * Get the question path from the question name
	 * This assumes that all names in the survey are unique
	 * rmpath
	 *
	static public String getQuestionPath(
			Connection sd, 
			int sId,
			String qName) throws SQLException {
		
		String path = null;
		
		String sqlGetQuestionPath = "select q.path " +
				" from question q, form f" +
				" where q.f_id = f.f_id " +
				" and f.s_id = ? " +
				" and q.qname = ?;";
		
		PreparedStatement pstmt = null;
		
		try {
		
			pstmt = sd.prepareStatement(sqlGetQuestionPath);
			pstmt.setInt(1, sId);
			pstmt.setString(2, qName);
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				path = rs.getString(1);	
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) { pstmt.close();}} catch (SQLException e) {}
		}
		
		return path;
	}
	*/
	
	/*
	 * Get the column name from the question name
	 * This assumes that all names in the survey are unique
	 */
	static public String getColumnName(
			Connection sd, 
			int sId,
			String qName) throws SQLException {
		
		String column_name = null;
		
		String sql = "select q.column_name " +
				" from question q, form f" +
				" where q.f_id = f.f_id " +
				" and f.s_id = ? " +
				" and q.qname = ?;";
		
		PreparedStatement pstmt = null;
		
		try {
		
			pstmt = sd.prepareStatement(sql);
			pstmt.setInt(1, sId);
			pstmt.setString(2, qName);
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				column_name = rs.getString(1);	
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) { pstmt.close();}} catch (SQLException e) {}
		}
		
		return column_name;
	}
	
	/*
	 * Get an access key to allow results for a form to be securely submitted
	 */
	public static String  getNewAccessKey(
			Connection sd, 
			String userIdent,
			String surveyIdent) throws SQLException {
		
		String key = null;
		int userId = -1;
		
		String sqlGetUserId = "select u.id from users u where u.ident = ?;";
		PreparedStatement pstmtGetUserId = null;
		 
		String sqlClearObsoleteKeys = "delete from dynamic_users " +
				" where expiry < now() " +
				" or expiry is null;";		
		PreparedStatement pstmtClearObsoleteKeys = null;
		
		String interval = "7 days";
		String sqlAddKey = "insert into dynamic_users (u_id, survey_ident, access_key, expiry) " +
				" values (?, ?, ?, timestamp 'now' + interval '" + interval + "');";		
		PreparedStatement pstmtAddKey = null;
		
		String sqlGetKey = "select access_key from dynamic_users where u_id = ?;";	
		PreparedStatement pstmtGetKey = null;
		
		log.info("GetAccessKey");
		try {
		
			/*
			 * Delete any expired keys
			 */
			pstmtClearObsoleteKeys = sd.prepareStatement(sqlClearObsoleteKeys);
			pstmtClearObsoleteKeys.executeUpdate();
			
			/*
			 * Get the user id
			 */
			pstmtGetUserId = sd.prepareStatement(sqlGetUserId);
			pstmtGetUserId.setString(1, userIdent);
			log.info("Get User id:" + pstmtGetUserId.toString() );
			ResultSet rs = pstmtGetUserId.executeQuery();
			if(rs.next()) {
				userId = rs.getInt(1);
			}
			
			/*
			 * Get the existing access key
			 */
			pstmtGetKey = sd.prepareStatement(sqlGetKey);
			pstmtGetKey.setInt(1, userId);
			rs = pstmtGetKey.executeQuery();
			if(rs.next()) {
				key = rs.getString(1);
			}
			
			/*
			 * Get a new key if necessary
			 */
			if(key == null) {
				
				/*
				 * Get the new access key
				 */
				key = String.valueOf(UUID.randomUUID());
				
				/*
				 * Save the key in the dynamic users table
				 */
				pstmtAddKey = sd.prepareStatement(sqlAddKey);
				pstmtAddKey.setInt(1, userId);
				pstmtAddKey.setString(2, surveyIdent);
				pstmtAddKey.setString(3, key);
				log.info("Add new key:" + pstmtAddKey.toString());
				pstmtAddKey.executeUpdate();
			}
			
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmtGetUserId != null) { pstmtGetUserId.close();}} catch (SQLException e) {}
			try {if (pstmtClearObsoleteKeys != null) { pstmtClearObsoleteKeys.close();}} catch (SQLException e) {}
			try {if (pstmtAddKey != null) { pstmtAddKey.close();}} catch (SQLException e) {}
			try {if (pstmtGetKey != null) { pstmtGetKey.close();}} catch (SQLException e) {}
		}
		
		return key;
	}
	
	/*
	 * Delete access keys for a user when they log out
	 */
	public static void  deleteAccessKeys(
			Connection sd, 
			String userIdent) throws SQLException {
		
		String sqlDeleteKeys = "delete from dynamic_users d where d.u_id in " +
				"(select u.id from users u where u.ident = ?);";
		PreparedStatement pstmtDeleteKeys = null;
		
		log.info("DeleteAccessKeys");
		try {
		
			/*
			 * Delete any keys for this user
			 */
			pstmtDeleteKeys = sd.prepareStatement(sqlDeleteKeys);
			pstmtDeleteKeys.setString(1, userIdent);
			pstmtDeleteKeys.executeUpdate();

			
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmtDeleteKeys != null) { pstmtDeleteKeys.close();}} catch (SQLException e) {}
		}
		
	}
	
	/*
	 * Get a dynamic user's details from their unique key
	 */
	public static String  getDynamicUser(
			Connection sd, 
			String key) throws SQLException {
		
		String userIdent = null;
		
		String sqlGetUserDetails = "select u.ident from users u, dynamic_users d " +
				" where u.id = d.u_id " +
				" and d.access_key = ? " +
				" and d.expiry > now();";
		PreparedStatement pstmtGetUserDetails = null;
		
		
		log.info("GetDynamicUser");
		try {
		
			/*
			 * Get the user id
			 */
			pstmtGetUserDetails = sd.prepareStatement(sqlGetUserDetails);
			pstmtGetUserDetails.setString(1, key);
			log.info("Get User details:" + pstmtGetUserDetails.toString() );
			ResultSet rs = pstmtGetUserDetails.executeQuery();
			if(rs.next()) {
				userIdent = rs.getString(1);
			}		
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmtGetUserDetails != null) { pstmtGetUserDetails.close();}} catch (SQLException e) {}
		}
		
		return userIdent;
	}
	/*
	 * Return true if this questions appearance means that choices come from an external file
	 */
	public static boolean isAppearanceExternalFile(String appearance) {
		if(appearance != null && appearance.toLowerCase().trim().contains("search(")) {
			return true;
		} else {
			return false;
		}
	}
		
	/*
	 * Get a list of options from an external file
	 */
	public static void getOptionsFromFile(
			Connection connectionSD,
			ArrayList<ChangeItem> ciList, 
			File csvFile,
			String csvFileName,
			String qName,
			int l_id,
			int qId,
			String qType,
			String qAppearance) {
		
		BufferedReader br = null;
		try {
			FileReader reader = new FileReader(csvFile);
			br = new BufferedReader(reader);
			CSVParser parser = new CSVParser();
	       
			// Get Header
			String line = br.readLine();
			String cols [] = parser.parseLine(line);
	       
			CSVFilter filter = new CSVFilter(cols, qAppearance);								// Get a filter
			ValueLabelCols vlc = getValueLabelCols(connectionSD, qId, qName, cols);		// Identify the columns in the CSV file that have the value and label

			while(line != null) {
				line = br.readLine();
				if(line != null) {
					String [] optionCols = parser.parseLine(line);
					if(filter.isIncluded(optionCols)) {
		    		   
						ChangeItem c = new ChangeItem();
						c.option = new Option();
						c.option.l_id = l_id;
						c.qName = qName;							// Add for logging
						c.fileName = csvFileName;				    // Add for logging
						c.qType = qType;
						c.option.externalLabel = optionCols[vlc.label];
						c.option.value = optionCols[vlc.value];
						//c.property.type = "option";
		    		  
						ciList.add(c);
		    		   
					} else {
						// ignore line
					}
				}
			}
	       
		       // TODO delete all file options that were not in the latest file (file version number)
		} catch (Exception e) {
			log.log(Level.SEVERE,"Error", e);
		} finally {
			try {br.close();} catch(Exception e) {};
		}
	}
		
	/*
	 * Return the columns in a CSV file that have the value and label for the given question	
	 */
	public static ValueLabelCols getValueLabelCols(Connection connectionSD, int qId, String qDisplayName, String [] cols) throws Exception {
		
		ValueLabelCols vlc = new ValueLabelCols();
		
		if(cols == null) {
			// No column in this CSV file so there are not going to be any matches
			return vlc;
		}
		/*
		 * Ignore language in the query, these values are codes and are (currently) independent of language
		 */
		PreparedStatement pstmt = null;
		String sql = "SELECT o.ovalue, t.value " +
				"from option o, translation t, question q " +  		
				"where o.label_id = t.text_id " +
				"and o.l_id = q.l_id " +
				"and q.q_id = ? " +
				"and externalfile ='false';";	
		
		try {
			pstmt = connectionSD.prepareStatement(sql);
			pstmt.setInt(1,  qId);
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				String valueName = rs.getString(1);
				String labelName = rs.getString(2);
				
				vlc.value = -1;
				vlc.label = -1;
				for(int i = 0; i < cols.length; i++) {
					if(cols[i].toLowerCase().equals(valueName.toLowerCase())) {
						vlc.value = i;
					} else if(cols[i].toLowerCase().equals(labelName.toLowerCase())) {
						vlc.label = i;
					}
				}
			} else {
				throw new Exception("The names of the columns to use in this csv file "
						+ "have not been set in question " + qDisplayName);
			}
		} catch (Exception e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			if (pstmt != null) try {pstmt.close();} catch(Exception e) {};
		}
		return vlc;
	}
	
	/*
	 * Get languages that have been used in a survey resulting in a translation entry
	 *  This is used to get languages for surveys loaded from xlfForm prior to the creation of the editor
	 *  After the creation of the editor the available languages, some of which may not have any translation entries, are 
	 *   stored in the languages table
	 */
	public static ArrayList<String> getLanguagesUsedInSurvey(Connection connectionSD, int sId) throws SQLException {
		
		PreparedStatement pstmtLanguages = null;
		
		ArrayList<String> languages = new ArrayList<String> ();
		try {
			String sqlLanguages = "select distinct t.language from translation t where s_id = ? order by t.language asc";
			pstmtLanguages = connectionSD.prepareStatement(sqlLanguages);
			
			pstmtLanguages.setInt(1, sId);
			ResultSet rs = pstmtLanguages.executeQuery();
			while(rs.next()) {
				languages.add(rs.getString(1));
			}
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmtLanguages != null) {pstmtLanguages.close();}} catch (SQLException e) {}
		}
		return languages;
	}
	
	/*
	 * Get languages from the languages table
	 */
	public static ArrayList<Language> getLanguages(Connection sd, int sId) throws SQLException {
		
		PreparedStatement pstmtLanguages = null;
		ArrayList<Language> languages = new ArrayList<Language> ();
		
		try {
			String sqlLanguages = "select id, language, seq from language where s_id = ? order by seq asc";
			pstmtLanguages = sd.prepareStatement(sqlLanguages);
			
			pstmtLanguages.setInt(1, sId);
			ResultSet rs = pstmtLanguages.executeQuery();
			while(rs.next()) {
				languages.add(new Language(rs.getInt(1), rs.getString(2)));
			}
			
			if(languages.size() == 0) {
				// Survey was loaded from an xlsForm and the languages array was not set, get languages from translations
				ArrayList<String> languageNames = GeneralUtilityMethods.getLanguagesUsedInSurvey(sd, sId);
				for(int i = 0; i < languageNames.size(); i++) {
					languages.add(new Language(-1, languageNames.get(i)));
				}
				GeneralUtilityMethods.setLanguages(sd, sId, languages);
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmtLanguages != null) {pstmtLanguages.close();}} catch (SQLException e) {}
		}
		
		return languages;
	}
	
	/*
	 * Set the languages in the language table
	 */
	public static void setLanguages(Connection sd, int sId, ArrayList<Language> languages) throws SQLException {
		
		PreparedStatement pstmtDelete = null;
		PreparedStatement pstmtInsert = null;
		PreparedStatement pstmtUpdate = null;
		PreparedStatement pstmtUpdateTranslations = null;
		
		try {
			String sqlDelete = "delete from language where id = ? and s_id = ?;";
			pstmtDelete = sd.prepareStatement(sqlDelete);
			
			String sqlInsert = "insert into language(s_id, language, seq) values(?, ?, ?);";
			pstmtInsert = sd.prepareStatement(sqlInsert);
			
			String sqlUpdate = "update language "
					+ "set language = ?, "
					+ "seq = ? "
					+ "where id = ? "
					+ "and s_id = ?";		// Security
			pstmtUpdate = sd.prepareStatement(sqlUpdate);
			
			String sqlUpdateTranslations = "update translation "
					+ "set language = ? "
					+ "where s_id = ? "
					+ "and language = (select language from language where id = ?);";
			pstmtUpdateTranslations = sd.prepareStatement(sqlUpdateTranslations);
			
			// Process each language in the list
			int seq = 0;
			for(int i = 0; i < languages.size(); i++) {
				
				Language language = languages.get(i);
				
				if(language.deleted) {
					// Delete language
					pstmtDelete.setInt(1,language.id);
					pstmtDelete.setInt(2,sId);
					
					log.info("Delete language: " + pstmtDelete.toString());
					pstmtDelete.executeUpdate();
					
				} else if (language.id > 0) {
					
					// Update the translations using this language 
					// (note: for historical reasons the language name is repeated in each translation rather than the language id)
					pstmtUpdateTranslations.setString(1, language.name);
					pstmtUpdateTranslations.setInt(2, sId);
					pstmtUpdateTranslations.setInt(3, language.id);
					
					log.info("Update Translations: " + pstmtUpdateTranslations.toString());
					pstmtUpdateTranslations.executeUpdate();
					
					// Update language name
					pstmtUpdate.setString(1, language.name);
					pstmtUpdate.setInt(2, seq);
					pstmtUpdate.setInt(3, language.id);
					pstmtUpdate.setInt(4, sId);
					
					log.info("Update Language: " + pstmtUpdate.toString());
					pstmtUpdate.executeUpdate();		
					
					seq++;
				} else if (language.id <= 0) {
					// insert language
					pstmtInsert.setInt(1, sId);
					pstmtInsert.setString(2, language.name);
					pstmtInsert.setInt(3, seq);
					
					log.info("Insert Language: " + pstmtInsert.toString());
					pstmtInsert.executeUpdate();	
					
					seq++;
				}
					
			}
		
			
		} catch(SQLException e) {
			try { sd.rollback();} catch (Exception ex){log.log(Level.SEVERE,"", ex);}
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmtDelete != null) {pstmtDelete.close();}} catch (SQLException e) {}
			try {if (pstmtInsert != null) {pstmtInsert.close();}} catch (SQLException e) {}
			try {if (pstmtUpdate != null) {pstmtUpdate.close();}} catch (SQLException e) {}
			try {if (pstmtUpdateTranslations != null) {pstmtUpdateTranslations.close();}} catch (SQLException e) {}
		}

	}
	
	/*
	 * Make sure media is consistent across all languages
	 * A future change may have media per language enabled
	 */
	public static void setMediaForLanguages(Connection sd, int sId, ArrayList<Language> languages) throws SQLException {
		
		//ArrayList<String> languages = new ArrayList<String> ();
		
		PreparedStatement pstmtGetLanguages = null;
		PreparedStatement pstmtGetMedia = null;
		PreparedStatement pstmtHasMedia = null;
		PreparedStatement pstmtDeleteMedia = null;
		PreparedStatement pstmtInsertMedia = null;
		
		String sqlHasMedia = "select count(*) from translation t "
					+ "where t.s_id = ? "
					+ "and t.type = ? "
					+ "and t.value = ? "
					+ "and t.text_id = ? "
					+ "and t.language = ?";
		
		String sqlDeleteMedia = "delete from translation where s_id = ? "
				+ "and type = ? "
				+ "and text_id = ? "
				+ "and language = ?";
		
		String sqlInsertMedia = "insert into translation (s_id, type, text_id, value, language) values (?, ?, ?, ?, ?); ";
		
		try {
			
			// 1. Get the media from the translation table ignoring language
			String sqlGetMedia = "select distinct t.type, t.value, t.text_id from translation t "
					+ "where t.s_id = ? "
					+ "and (t.type = 'image' or t.type = 'audio' or t.type = 'video'); ";
				
			pstmtGetMedia = sd.prepareStatement(sqlGetMedia);
			pstmtGetMedia.setInt(1, sId);
			
			log.info("Get distinct media: " + pstmtGetMedia.toString());
			ResultSet rs = pstmtGetMedia.executeQuery();
			
			/*
			 * Prepare statments used within the loop
			 */
			pstmtHasMedia = sd.prepareStatement(sqlHasMedia);
			pstmtHasMedia.setInt(1, sId);
			
			pstmtDeleteMedia = sd.prepareStatement(sqlDeleteMedia);
			pstmtDeleteMedia.setInt(1, sId);
			
			pstmtInsertMedia = sd.prepareStatement(sqlInsertMedia);
			pstmtInsertMedia.setInt(1, sId);
			
			while (rs.next()) {
				String type = rs.getString(1);
				String value = rs.getString(2);
				String text_id = rs.getString(3);
				
				System.out.println("-------------");
				System.out.println("    " + rs.getString(1));
				System.out.println("    " + rs.getString(2));
				System.out.println("    " + rs.getString(3));
				
				// 2. Check that each language has this media
				for(Language language : languages) {
					String languageName = language.name;
					boolean hasMedia = false;
					
					pstmtHasMedia.setString(2, type);
					pstmtHasMedia.setString(3, value);
					pstmtHasMedia.setString(4, text_id);
					pstmtHasMedia.setString(5, languageName);
					
					log.info("Has Media: " + pstmtHasMedia.toString());
					ResultSet rsHasMedia = pstmtHasMedia.executeQuery();
					if(rsHasMedia.next()) {
						if(rsHasMedia.getInt(1) > 0) {
							hasMedia = true;
						}
					}
					
					System.out.println("        Language: " + languageName + " : " + hasMedia);
					if(!hasMedia) {
						
						// 3.  Delete any translation entries for the media that have the wrong value
						pstmtDeleteMedia.setString(2, type);
						pstmtDeleteMedia.setString(3, text_id);
						pstmtDeleteMedia.setString(4, languageName);
						
						log.info("SQL delete media: " + pstmtDeleteMedia.toString());
						pstmtDeleteMedia.executeUpdate();
						
						// 4. Insert this translation value
						pstmtInsertMedia.setString(2, type);
						pstmtInsertMedia.setString(3, text_id);
						pstmtInsertMedia.setString(4, value);
						pstmtInsertMedia.setString(5, languageName);
						
						log.info("SQL insert media: " + pstmtInsertMedia.toString());
						pstmtInsertMedia.executeUpdate();
					}
					
				}
				
				
			}
			
			
		
					
			
		
			
		} catch(SQLException e) {
			try { sd.rollback();} catch (Exception ex){log.log(Level.SEVERE,"", ex);}
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmtGetLanguages != null) {pstmtGetLanguages.close();}} catch (SQLException e) {}
			try {if (pstmtGetMedia != null) {pstmtGetMedia.close();}} catch (SQLException e) {}
			try {if (pstmtHasMedia != null) {pstmtHasMedia.close();}} catch (SQLException e) {}
			try {if (pstmtDeleteMedia != null) {pstmtDeleteMedia.close();}} catch (SQLException e) {}
			try {if (pstmtInsertMedia != null) {pstmtInsertMedia.close();}} catch (SQLException e) {}
		
		}

	}
	
	/*
	 * Get the default language for a survey
	 */
	public static String getDefaultLanguage(Connection connectionSD, int sId) throws SQLException {
		
		PreparedStatement pstmtDefLang = null;
		PreparedStatement pstmtDefLang2 = null;
		
		String deflang = null;
		try {
			
			String sqlDefLang = "select def_lang from survey where s_id = ?; ";
			pstmtDefLang = connectionSD.prepareStatement(sqlDefLang);
			pstmtDefLang.setInt(1, sId);
			ResultSet resultSet = pstmtDefLang.executeQuery();
			if (resultSet.next()) {
				deflang = resultSet.getString(1);
				if(deflang == null) {
					// Just get the first language in the list	
					String sqlDefLang2 = "select distinct language from translation where s_id = ?; ";
					pstmtDefLang2 = connectionSD.prepareStatement(sqlDefLang2);
					pstmtDefLang2.setInt(1, sId);
					ResultSet resultSet2 = pstmtDefLang2.executeQuery();
					if (resultSet2.next()) {
						deflang = resultSet2.getString(1);
					}
				}
			}
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmtDefLang != null) {pstmtDefLang.close();}} catch (SQLException e) {}
			try {if (pstmtDefLang2 != null) {pstmtDefLang2.close();}} catch (SQLException e) {}
		}
		return deflang;
	}
	
	/*
	 * Get the answer for a specific question and a specific instance
	 */
	public static ArrayList<String> getResponseForQuestion(Connection sd, Connection results, int sId, int qId, String instanceId) throws SQLException {
		
		PreparedStatement pstmtQuestion = null;
		PreparedStatement pstmtOption = null;
		PreparedStatement pstmtResults = null;
		
		String sqlQuestion = "select qType, qName, f_id from question where q_id = ?";
		String sqlOption = "select o.ovalue, o.column_name from option o, question q where q.q_id = ? and q.l_id = o.l_id";
		
		String qType = null;
		String qName = null;
		int fId = 0;
		
		ArrayList<String> responses = new ArrayList<String> ();
		try {
			pstmtQuestion = sd.prepareStatement(sqlQuestion);
			pstmtQuestion.setInt(1, qId);
			log.info("GetResponseForQuestion: " + pstmtQuestion.toString());
			ResultSet rs = pstmtQuestion.executeQuery();
			if(rs.next()) {
				qType = rs.getString(1);
				qName = rs.getString(2);
				fId = rs.getInt(3);
				
				ArrayList<String> tableStack = getTableStack(sd, fId);
				ArrayList<String> options = new ArrayList<String> ();
				
				// First table is for the question, last is for the instance id
				StringBuffer query = new StringBuffer();		
				
				// Add the select
				if(qType.equals("select")) {
					pstmtOption = sd.prepareStatement(sqlOption);
					pstmtOption.setInt(1, qId);
					
					log.info("Get Options: " + pstmtOption.toString());
					ResultSet rsOptions = pstmtOption.executeQuery();
					
					query.append("select ");
					int count = 0;
					while(rsOptions.next()) {
						String oValue = rsOptions.getString(1);
						String oColumnName = rsOptions.getString(2);
						options.add(oValue);
						
						if(count > 0) {
							query.append(",");
						}
						query.append(" t0.");
						query.append(qName);
						query.append("__");
						query.append(oColumnName);
						query.append(" as ");
						query.append(oValue);
						count++;
					}
					query.append(" from ");
				} else {
					query.append("select t0." + qName + " from ");
				}
				
				// Add the tables
				for(int i = 0; i < tableStack.size(); i++) {
					if(i > 0) {
						query.append(",");
					}
					query.append(tableStack.get(i));
					query.append(" t");
					query.append(i);
				}
				
				// Add the join
				query.append(" where ");
				if(tableStack.size() > 1) {
					for(int i = 1; i < tableStack.size(); i++) {
						if(i > 1) {
							query.append(" and ");
						}
						query.append("t");
						query.append(i - 1);
						query.append(".parkey = t");
						query.append(i);
						query.append(".prikey");
					}
				}
				
				// Add the instance
				if(tableStack.size() > 1) {
					query.append(" and ");
				}
				query.append(" t");
				query.append(tableStack.size() - 1);
				query.append(".instanceid = ?");
				
				pstmtResults = results.prepareStatement(query.toString());
				pstmtResults.setString(1, instanceId);
				log.info("Get results for a question: " + pstmtResults.toString());
				
				rs = pstmtResults.executeQuery();
				while(rs.next()) {
					if(qType.equals("select")) {
						for(String option : options) {
							int isSelected = rs.getInt(option);
							
							if(isSelected > 0) { 
								String email=option.replaceFirst("_amp_", "@");
								email=email.replaceAll("_dot_", ".");
								log.info("******** " + email);
								String emails[] = email.split(",");
								for(int i = 0; i < emails.length; i++) {
									responses.add(emails[i]);
								}
							}
						}
					} else {
						log.info("******** " + rs.getString(1));
						String email = rs.getString(1);
						if(email != null) {
							String [] emails = email.split(",");
							for(int i = 0; i < emails.length; i++) {
								responses.add(emails[i]);
							}
						}
						
					}
				}
			}
	
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmtQuestion != null) {pstmtQuestion.close();}} catch (SQLException e) {}
			try {if (pstmtOption != null) {pstmtOption.close();}} catch (SQLException e) {}
			try {if (pstmtResults != null) {pstmtResults.close();}} catch (SQLException e) {}
		}
		return responses;
	}
	
	/*
	 * Starting from the past in question get all the tables up to the highest parent that are part of this survey
	 */
	public static ArrayList<String> getTableStack(Connection sd, int fId) throws SQLException {
		ArrayList<String> tables = new ArrayList<String> ();
		
		PreparedStatement pstmtTable = null;
		String sqlTable = "select table_name, parentform from form where f_id = ?";
		
		try {
			pstmtTable = sd.prepareStatement(sqlTable);
			
			while (fId > 0) {
				pstmtTable.setInt(1, fId);
				ResultSet rs = pstmtTable.executeQuery();
				if(rs.next()) {
					tables.add(rs.getString(1));
					fId = rs.getInt(2);
				} else {
					fId = 0;
				}
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmtTable != null) {pstmtTable.close();}} catch (SQLException e) {}
		}	
		
		return tables;
		
	}
	
	/*
	 * Return column type if the passed in column name is in the table else return null
	 */
	public static String columnType(Connection connection, String tableName, String columnName) throws SQLException {
		
		String type = null;
		
		String sql = "select data_type from information_schema.columns where table_name = ? " +
				"and column_name = ?;";
		PreparedStatement pstmt = null;
		
		
		try {
			pstmt = connection.prepareStatement(sql);
			pstmt.setString(1,  tableName);
			pstmt.setString(2,  columnName);
			
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				type = rs.getString(1);
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) {pstmt.close();}} catch (SQLException e) {}
		}	
		
		return type;
		
	}
	
	/*
	 * Return a list of results columns for a form
	 */
	public static ArrayList<Column> getColumnsInForm(Connection sd, 
			Connection cResults, 
			int formParent,
			int f_id,
			String table_name,
			boolean includeRO,
			boolean includeParentKey,
			boolean includeBad,
			boolean includeInstanceId) throws SQLException {
		
		ArrayList<Column> columnList = new ArrayList<Column>();
		ArrayList<Column> realQuestions = new ArrayList<Column> ();	// Temporary array so that all property questions can be added first
		boolean uptodateTable = false;	// Set true if the results table has the latest meta data columns
		
		// SQL to get the questions
		String sqlQuestions = "select qname, qtype, column_name, q_id, readonly, source_param, path "
				+ "from question where f_id = ? "
				+ "and source is not null "
				+ "and published = 'true' "
				+ "order by seq";
		PreparedStatement pstmtQuestions = sd.prepareStatement(sqlQuestions);
		
		// Get column names for select multiple questions
		String sqlSelectMultiple = "select distinct o.column_name, o.ovalue, o.seq "
				+ "from option o, question q "
				+ "where o.l_id = q.l_id "
				+ "and q.q_id = ? "
				+ "and o.externalfile = ? "
				+ "and o.published = 'true' "
				+ "order by o.seq;";
		PreparedStatement pstmtSelectMultiple = sd.prepareStatement(sqlSelectMultiple);
		
		Column c = new Column();
		c.name = "prikey";
		c.humanName = "prikey";
		c.qType = "";
		columnList.add(c);
		
		// Add HRK if it has been specified
		if(GeneralUtilityMethods.columnType(cResults, table_name, "_hrk") != null) {
			c = new Column();
			c.name = "_hrk";
			c.humanName = "Key";
			c.qType = "";
			columnList.add(c);
		}
		
		if(includeParentKey) {
			c = new Column();
			c.name = "parkey";
			c.humanName = "parkey";
			c.qType = "";
			columnList.add(c);
		}
		
		if(includeBad) {
			c = new Column();
			c.name = "_bad";
			c.humanName = "_bad";
			c.qType = "";
			columnList.add(c);
			
			c = new Column();
			c.name = "_bad_reason";
			c.humanName = "_bad_reason";
			c.qType = "";
			columnList.add(c);
		}
		
		// For the top level form add default columns that are not in the question list
		if(formParent == 0) {
			
			c = new Column();
			c.name = "_user";
			c.humanName = "User";
			c.qType = "";
			columnList.add(c);
			
			if(GeneralUtilityMethods.columnType(cResults, table_name, "_survey_notes") != null) {
				uptodateTable = true;		// This is the latest meta column that was added
			}
			
			if(uptodateTable || GeneralUtilityMethods.columnType(cResults, table_name, "_upload_time") != null) {
				
				
				c = new Column();
				c.name = "_upload_time";
				c.humanName = "Upload Time";
				c.qType = "";
				columnList.add(c);
				
				c = new Column();
				c.name = "_s_id";
				c.humanName = "Survey Name";
				c.qType = "";
				columnList.add(c);
			}
			
			if(uptodateTable || GeneralUtilityMethods.columnType(cResults, table_name, "_version") != null) {
				c = new Column();
				c.name = "_version";
				c.humanName = "Version";
				c.qType = "";
				columnList.add(c);
			}
			
			if(uptodateTable || GeneralUtilityMethods.columnType(cResults, table_name, "_complete") != null) {
				c = new Column();
				c.name = "_complete";
				c.humanName = "Complete";
				c.qType = "";
				columnList.add(c);
			}
			
			if(includeInstanceId && 
					(uptodateTable || GeneralUtilityMethods.columnType(cResults, table_name, "instanceid") != null)) {
				c = new Column();
				c.name = "instanceid";
				c.humanName = "instanceid";
				c.qType = "";
				columnList.add(c);
			}
			
			if(uptodateTable) {
				c = new Column();
				c.name = "_survey_notes";
				c.humanName = "Survey Notes";
				c.qType = "";
				columnList.add(c);
				
				c = new Column();
				c.name = "_location_trigger";
				c.humanName = "Location Trigger";
				c.qType = "";
				columnList.add(c);
			}
			
			
		}
		
		try {
			pstmtQuestions.setInt(1, f_id);
			
			log.info("SQL: Get columns:" + pstmtQuestions.toString());
			ResultSet rsQuestions = pstmtQuestions.executeQuery();
			
			/*
			 * Get columns
			 */
			while(rsQuestions.next()) {
				
				String question_human_name = rsQuestions.getString(1);
				String qType = rsQuestions.getString(2);
				String question_column_name = rsQuestions.getString(3);
				int qId = rsQuestions.getInt(4);
				boolean ro = rsQuestions.getBoolean(5);
				String source_param = rsQuestions.getString(6);
				String path = rsQuestions.getString(7);
				
				String cName = question_column_name.trim().toLowerCase();
				if(cName.equals("parkey") ||	cName.equals("_bad") ||	cName.equals("_bad_reason")
						||	cName.equals("_task_key") ||	cName.equals("_task_replace") ||	cName.equals("_modified")
						||	cName.equals("_instanceid") ||	cName.equals("instanceid")) {
					continue;
				}
				
				if(!includeRO && ro) {
					continue;			// Drop read only columns if they are not selected to be exported				
				}
				
				if(qType.equals("select")) {
					
					// Check if there are any choices from an external csv file in this select multiple
					boolean external = GeneralUtilityMethods.hasExternalChoices(sd, qId);
					
					// Get the choices, either all from an external file or all from an internal file but not both
					pstmtSelectMultiple.setInt(1, qId);
					pstmtSelectMultiple.setBoolean(2, external);
					ResultSet rsMultiples = pstmtSelectMultiple.executeQuery();
					
					HashMap<String, String> uniqueColumns = new HashMap<String, String> (); 
					while (rsMultiples.next()) {
						String uk = question_column_name + "xx" + rsMultiples.getString(2);		// Column name can be randomised so don't use it for uniqueness
						
						if(uniqueColumns.get(uk) == null) {
							uniqueColumns.put(uk, uk);
						
							if(question_column_name.startsWith("why")) {
								System.out.println(question_column_name + " : " + rsMultiples.getString(1));
							}
							c = new Column();
							c.name = question_column_name + "__" + rsMultiples.getString(1);
							c.humanName = question_human_name + " - " + rsMultiples.getString(2);
							c.question_name = question_column_name;
							c.option_name = rsMultiples.getString(1);
							c.qId = qId;
							c.qType = qType;
							c.ro = ro;
							realQuestions.add(c);
						}
					}
				} else {
					c = new Column();
					c.name = question_column_name;
					c.question_name = question_column_name;
					c.humanName = question_human_name;
					c.qId = qId;
					c.qType = qType;
					c.ro = ro;
					if(GeneralUtilityMethods.isPropertyType(source_param, question_column_name, path)) {
						columnList.add(c);
					} else {
						realQuestions.add(c);
					}
				}
				
			}
		} finally {
			try {if (pstmtQuestions != null) {pstmtQuestions.close();	}} catch (SQLException e) {	}
			try {if (pstmtSelectMultiple != null) {pstmtSelectMultiple.close();	}} catch (SQLException e) {	}
		}
		
		columnList.addAll(realQuestions);		// Add the real questions after the property questions
		
		
		return columnList;
	}
	
	/*
	 * Add the management columns for the survey
	 */
	public static void addManagementColumns(ArrayList<Column> columnList) {
		// TODO check that these exist
		// TODO Possibly the columns should vary with survey
		// TODO Possibly there could be more than one set of managment columns for a single survey
		
		// Hardcoded for now
		Column c = new Column();
		c.name = "_mgmt_responsible";
		c.humanName = "Responsible Person";
		c.qType = "string";
		columnList.add(c);
		
		c = new Column();
		c.name = "_mgmt_action_deadline";
		c.humanName = "Action Deadline";
		c.qType = "date";
		columnList.add(c);
		
		c = new Column();
		c.name = "_mgmt_action_date";
		c.humanName = "Date of Action";
		c.qType = "date";
		columnList.add(c);
		
		c = new Column();
		c.name = "_mgmt_response_status";
		c.humanName = "Response Status";
		c.qType = "calculate";
		c.calculation = "CASE "
				+ "WHEN _mgmt_action_deadline >= _mgmt_action_date THEN 'Deadline met' "
				+ "WHEN _mgmt_action_deadline < _mgmt_action_date THEN 'Done with delay' "
				+ "WHEN _mgmt_action_deadline > now() THEN 'In the pipeline' "
				+ "WHEN _mgmt_action_deadline is null THEN 'In the pipeline' "
	            + "ELSE 'Deadline crossed' "
	            + "END";
		columnList.add(c);
		
		c = new Column();
		c.name = "_mgmt_action_taken";
		c.humanName = "Action Taken";
		c.qType = "string";
		columnList.add(c);
		
		c = new Column();
		c.name = "_mgmt_address_recommendation";
		c.humanName = "Does the Action Address the Recommendation";
		c.qType = "string";
		columnList.add(c);
		
		c = new Column();
		c.name = "_mgmt_comment";
		c.humanName = "Comment";
		c.qType = "string";
		columnList.add(c);
		
	}
	
	/*
	 * Return true if this question is a property type question like deviceid
	 */
	public static boolean isPropertyType(String source_param, String name, String path) {
		
		boolean isProperty;
		
		if(source_param != null && 
				(source_param.equals("deviceid") || source_param.equals("start") || source_param.equals("end"))) {
			
			isProperty = true;
			
		} else if(name != null & (name.equals("_instanceid") || name.equals("_task_key"))) {
			
			isProperty = true;
			
		} else if(path != null && path.startsWith("/main/meta")) {
			
			isProperty = true;
		} else {
			isProperty = false;
		}
		
		return isProperty;
	}
	
	/*
	 * Returns the SQL fragment that makes up the date range restriction
	 */
	public static String getDateRange(Date startDate, Date endDate, String dateTable, String dateName) {
		String sqlFrag = "";
		boolean needAnd = false;
		
		if(startDate != null) {
			sqlFrag += dateTable + "." + dateName + " >= ? ";
			needAnd = true;
		}
		if(endDate != null) {
			if(needAnd) {
				sqlFrag += "and ";
			}
			sqlFrag += dateTable + "." + dateName + " < ? ";
		}

		return sqlFrag;
	}
	
	/*
	 * Add the filename to the response
	 */
	public static void setFilenameInResponse(String filename, HttpServletResponse response) {

		String escapedFileName = null;
		
		log.info("Setting filename in response: " + filename);
		if(filename == null) {
			filename = "survey";
		}
		try {
			escapedFileName = URLDecoder.decode(filename, "UTF-8");
			escapedFileName = URLEncoder.encode(escapedFileName, "UTF-8");
		} catch (Exception e) {
			log.log(Level.SEVERE, "Encoding Filename Error", e);
		}
		escapedFileName = escapedFileName.replace("+", " "); // Spaces ok for file name within quotes
		escapedFileName = escapedFileName.replace("%2C", ","); // Commas ok for file name within quotes
		
		response.setHeader("Content-Disposition", "attachment; filename=\"" + escapedFileName +"\"");	
		response.setStatus(HttpServletResponse.SC_OK);	
	}
	
	/*
	 * Get the choice filter from an xform nodeset
	 */
	public static String getChoiceFilterFromNodeset(String nodeset, boolean xlsName) {
		
		StringBuffer choice_filter = new StringBuffer("");
		String [] filterParts = null;
		
		if(nodeset != null) {
			int idx = nodeset.indexOf('[');
			int idx2 = nodeset.indexOf(']');
			if(idx > -1 && idx2 > idx) {
				filterParts = nodeset.substring(idx + 1, idx2).trim().split("\\s+");
				for(int i = 0; i < filterParts.length; i++) {
					if(filterParts[i].startsWith("/")) {
						choice_filter.append(xpathNameToName(filterParts[i], xlsName).trim() + " ");
					} else {
						choice_filter.append(filterParts[i].trim() + " ");
					}
				}
				
			}
		}
		
		return choice_filter.toString().trim();
		
	}

	/*
	 * Convert all xml fragments embedded in the supplied string to names
	 * ODK uses an html fragment <output/> to show values from questions in labels
	 */
	public static String convertAllEmbeddedOutput(String inputEsc, boolean xlsName) {
		
		StringBuffer output = new StringBuffer("");
		int idx = 0;
		String input = unesc(inputEsc);
		
		if(input != null) {
			
			while(idx >= 0) {
				
				idx = input.indexOf("<output");
				
				if(idx >= 0) {
					output.append(input.substring(0, idx));
					input = input.substring(idx + 1);
					idx = input.indexOf(">");
					if(idx >= 0) {
						String outputTxt = input.substring(0, idx + 1);
						input = input.substring(idx + 1);
						String [] parts = outputTxt.split("\\s+");
						for(int i = 0; i < parts.length; i++) {
							if(parts[i].startsWith("/")) {
								output.append(xpathNameToName(parts[i], xlsName).trim());
							} else {
								// ignore
							}		
						}
					} else {
						output.append(input);
					}
				} else {
					output.append(input);
				}
			}	
			
		}
		
		return output.toString().trim();
	}
	
	/*
	 * Convert all xPaths in the supplied string to names
	 */
	public static String convertAllXpathNames(String input, boolean xlsName) {
		StringBuffer output = new StringBuffer("");
		String [] parts = null;
		
		if(input != null) {
			
			parts = input.trim().split("\\s+");
			for(int i = 0; i < parts.length; i++) {
				if(parts[i].startsWith("/")) {
					output.append(xpathNameToName(parts[i], xlsName).trim() + " ");
				} else {
					output.append(parts[i].trim() + " ");
				}
				
			}
		}
		
		return output.toString().trim();
	}
	
	/*
	 * Convert an xpath name to just a name
	 */
	public static String xpathNameToName(String xpath, boolean xlsName) {
		String name = null;
		
		int idx = xpath.lastIndexOf("/");
		if(idx >= 0) {
			name = xpath.substring(idx + 1);
			if(xlsName) {
				name = "${" + name + "}";
			}
		}
		return name;
	}
	
	/*
	 * Convert names in xls format ${ } to an SQL query
	 */
	public static String convertAllxlsNamesToQuery(String input, int sId, Connection sd) throws SQLException {
		
		if(input == null) {
			return null;
		} else if(input.trim().length() == 0) {
			return null;
		}
		
		
		StringBuffer output = new StringBuffer("");
		String item;
		
		Pattern pattern = Pattern.compile("\\$\\{.+?\\}");
		java.util.regex.Matcher matcher = pattern.matcher(input);
		int start = 0;
		while (matcher.find()) {
			
			String matched = matcher.group();
			String qname = matched.substring(2, matched.length() - 1);
			System.out.println("Matched: " + qname);
			
			// Add any text before the match
			int startOfGroup = matcher.start();
			item = input.substring(start, startOfGroup).trim();
			if(item.length() > 0) {
				if(output.length() > 0) {
					output.append(" || ");
				}
				output.append('\'');
				output.append(item);
				output.append('\'');
			}
			
			// Add the column name
			if(output.length() > 0) {
				output.append(" || ");
			}
			output.append(getColumnName(sd, sId, qname));

			// Reset the start
			start = matcher.end();
						
		}
		
		// Get the remainder of the string
		if(start < input.length()) {
			item = input.substring(start).trim();
			if(item.length() > 0) {
				if(output.length() > 0) {
					output.append(" || ");
				}
				output.append('\'');
				output.append(item);
				output.append('\'');
			}
		}
		
		System.out.println("HRK: " + output.toString().trim());
		return output.toString().trim();
	}
	
	/*
	 * Translate a question type from its representation in the database to the survey model used for editing
	 */
	public static String translateTypeFromDB(String in, boolean readonly, boolean visible) {
		
		String out = in;
		
		if(in.equals("string") && readonly) {
			out = "note";
		} else if(in.equals("string") && !visible) {
			out = "calculate";
		}
		
		return out;
		
	}
	
	/*
	 * Translate a question type from its representation in the survey model to the database
	 */
	public static String translateTypeToDB(String in, String name) {
		
		String out = in;
		
		if(in.equals("note")) {
			out = "string";
		} else if(in.equals("begin repeat") && name.startsWith("geopolygon")) {
			out = "geopolygon";
		} else if(in.equals("begin repeat") && name.startsWith("geolinestring")) {
			out = "geolinestring";
		}
		
		return out;
		
	}
	
	/*
	 * Get the readonly value for a question as stored in the database
	 */
	public static boolean translateReadonlyToDB(String type, boolean in) {
		
		boolean out = in;
		
		if(type.equals("note")) {
			out = true;
		}
		
		return out;
		
	}
	
	// Get the timestamp
	public static Timestamp getTimeStamp() {
		 
		java.util.Date today = new java.util.Date();
		return new Timestamp(today.getTime());
	 
	}
	
	/*
	 * Check to see if there are any choices from an external file for a question
	 */
	public static boolean hasExternalChoices(Connection sd, int qId) throws SQLException {
		
		boolean external = false;
		String sql = "select count(*) from option o, question q where o.l_id = q.l_id and q.q_id = ? and o.externalfile = 'true';";
		PreparedStatement pstmt = null;
		
		try {
			pstmt = sd.prepareStatement(sql);
			pstmt.setInt(1, qId);
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				if(rs.getInt(1) > 0) {
					external = true;
				}
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) {pstmt.close();}} catch (SQLException e) {}
		}	
		
		return external;
	}
	
	/*
	 * Check to see if there are any choices from an external file for a question
	 */
	public static boolean listHasExternalChoices(Connection sd, int listId) throws SQLException {
		
		boolean external = false;
		String sql = "select count(*) from option o where o.l_id = ? and o.externalfile = 'true';";
		PreparedStatement pstmt = null;
		
		try {
			pstmt = sd.prepareStatement(sql);
			pstmt.setInt(1, listId);
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				if(rs.getInt(1) > 0) {
					external = true;
				}
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) {pstmt.close();}} catch (SQLException e) {}
		}	
		
		return external;
	}
	
	/*
	 * Re-sequence options starting from 0
	 */
	public static void cleanOptionSequences(Connection sd, int listId) throws SQLException {
		
		String sql = "select o_id, seq from option where l_id = ? order by seq asc;";
		PreparedStatement pstmt = null;
		
		String sqlUpdate = "update option set seq = ? where o_id = ?;";
		PreparedStatement pstmtUpdate = null;
		
		try {
			pstmt = sd.prepareStatement(sql);
			pstmt.setInt(1, listId);
			
			pstmtUpdate = sd.prepareStatement(sqlUpdate);
			
			ResultSet rs = pstmt.executeQuery();
			int newSeq = 0;
			while(rs.next()) {
				int oId = rs.getInt(1);
				int seq = rs.getInt(2);
				if(seq != newSeq) {
					pstmtUpdate.setInt(1, newSeq);
					pstmtUpdate.setInt(2, oId);
					
					log.info("Updating sequence for list id: " + listId + " : " + pstmtUpdate.toString());
					pstmtUpdate.execute();
				}
				newSeq++;
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) {pstmt.close();}} catch (SQLException e) {}
			try {if (pstmtUpdate != null) {pstmtUpdate.close();}} catch (SQLException e) {}
		}	
		
	}
	
	/*
	 * Re-sequence questions starting from 0
	 */
	public static void cleanQuestionSequences(Connection sd, int fId) throws SQLException {
		
		String sql = "select q_id, seq, qname from question where f_id = ? order by seq asc;";
		PreparedStatement pstmt = null;
		
		String sqlUpdate = "update question set seq = ? where q_id = ?;";
		PreparedStatement pstmtUpdate = null;
		
		try {
			pstmt = sd.prepareStatement(sql);
			pstmt.setInt(1, fId);
			
			pstmtUpdate = sd.prepareStatement(sqlUpdate);
			
			ResultSet rs = pstmt.executeQuery();
			int newSeq = 0;
			boolean inMeta = false;
			while(rs.next()) {
				int qId = rs.getInt(1);
				int seq = rs.getInt(2);
				String qname = rs.getString(3);
				
				// Once we reach the meta group ensure their sequence remains well after any other questions
				if(qname.equals("meta")) {
					newSeq += 5000;
				}
				
				if(seq != newSeq) {
					pstmtUpdate.setInt(1,newSeq);
					pstmtUpdate.setInt(2, qId);
					
					log.info("Updating question sequence for form id: " + fId + " : " + pstmtUpdate.toString());
					pstmtUpdate.execute();
				}
				newSeq++;
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) {pstmt.close();}} catch (SQLException e) {}
			try {if (pstmtUpdate != null) {pstmtUpdate.close();}} catch (SQLException e) {}
		}	
		
	}
	
	/*
	 * Get the id from the list name and survey Id
	 * If the list does not exist then create it
	 */
	public static int getListId(Connection sd, int sId, String name) throws SQLException {
		int listId = 0;
		
		PreparedStatement pstmtGetListId = null;
		String sqlGetListId = "select l_id from listname where s_id = ? and name = ?;";

		PreparedStatement pstmtListName = null;
		String sqlListName = "insert into listname (s_id, name) values (?, ?);";
		
		try {
			pstmtGetListId = sd.prepareStatement(sqlGetListId);
			pstmtGetListId.setInt(1, sId);
			pstmtGetListId.setString(2, name);
			
			log.info("SQL: Get list id: " + pstmtGetListId.toString());
			ResultSet rs = pstmtGetListId.executeQuery();
			if(rs.next()) {
				listId = rs.getInt(1);
			} else {	// Create listname
				pstmtListName = sd.prepareStatement(sqlListName, Statement.RETURN_GENERATED_KEYS);
				pstmtListName.setInt(1, sId);
				pstmtListName.setString(2, name);
				
				log.info("SQL: Create list name: " + pstmtListName.toString());
				
				pstmtListName.executeUpdate();
				
				rs = pstmtListName.getGeneratedKeys();
				rs.next();
				listId = rs.getInt(1);
				
			}
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {			
			try {if (pstmtGetListId != null) {pstmtGetListId.close();}} catch (SQLException e) {}
			try {if (pstmtListName != null) {pstmtListName.close();}} catch (SQLException e) {}
		}	
			
		
		return listId;
	}
	
	/*
	 * Get manifest parameters from appearance
	 */
	public static ArrayList<String> getManifestParams(Connection sd, int qId, String property, String filename, boolean isAppearance) throws SQLException {
		ArrayList<String> params = null;
		
		PreparedStatement pstmt = null;
		String sql = "SELECT o.ovalue, t.value " +
				"from option o, translation t, question q " +  		
				"where o.label_id = t.text_id " +
				"and o.l_id = q.l_id " +
				"and q.q_id = ? " +
				"and externalfile ='false';";
		
		try {
			pstmt = sd.prepareStatement(sql);
		
			// Check to see if this appearance references a manifest file
			if(property != null && (property.contains("search(") || property.contains("pulldata("))) {
				// Yes it references a manifest
				
				int idx1 = property.indexOf('(');
				int idx2 = property.indexOf(')');
				if(idx1 > 0 && idx2 > idx1) {
					String criteriaString = property.substring(idx1 + 1, idx2);
					
					String criteria [] = criteriaString.split(",");
					if(criteria.length > 0) {
						
						if(criteria[0] != null && criteria[0].length() > 2) {	// allow for quotes
							String appFilename = criteria[0].trim();
							appFilename = appFilename.substring(1, appFilename.length() -1);
							
							if(filename.equals(appFilename)) {	// We want this one
								log.info("We have found a manifest link to " + filename);
								
								if(isAppearance) {
									
									params = getRefQuestionsSearch(criteria);
									
									// Need to get columns from choices
									pstmt.setInt(1, qId);
									log.info("Getting search columns from choices: " + pstmt.toString());
									ResultSet rs = pstmt.executeQuery();
									while(rs.next()) {
										if(params == null) {
											params = new ArrayList<String> ();
										}
										params.add(rs.getString(1));
										params.add(rs.getString(2));
									}
								} else {
									params = getRefQuestionsPulldata(criteria);
								}
							
							} 
	
						}
					}
				}
			} 
		} finally {
			if(pstmt != null) try {pstmt.close();} catch(Exception e) {}
		}
		
		return params;
	}
	
	/*
	 * Add to a survey level manifest String, a manifest from an appearance attribute
	 */
	public static ManifestInfo addManifestFromAppearance(String appearance, String inputManifest) {
		
		ManifestInfo mi = new ManifestInfo();
		ArrayList<String> refQuestions = null;
		String manifestType = null;
		
		mi.manifest = inputManifest;
		mi.changed = false;
		
		// Check to see if this appearance references a manifest file
		if(appearance != null && appearance.toLowerCase().trim().contains("search(")) {
			// Yes it references a manifest
			
			int idx1 = appearance.indexOf('(');
			int idx2 = appearance.indexOf(')');
			if(idx1 > 0 && idx2 > idx1) {
				String criteriaString = appearance.substring(idx1 + 1, idx2);
				
				String criteria [] = criteriaString.split(",");
				if(criteria.length > 0) {
					
					if(criteria[0] != null && criteria[0].length() > 2) {	// allow for quotes
						String filename = criteria[0].trim();
						filename = filename.substring(1, filename.length() -1);
						
						if(filename.startsWith("linked_s")) {	// Linked survey
							log.info("We have found a manifest link to " + filename);
							refQuestions = getRefQuestionsSearch(criteria);
							manifestType = "linked";
							System.out.println("RefQuestions: " + refQuestions.toString());
						} else {
							filename += ".csv";
							manifestType = "csv";
							log.info("We have found a manifest file " + filename);
						}
						
						updateManifest(mi, filename, refQuestions, manifestType);

					}
				}
			}
		} 
		return mi;
			
	}
	
	/*
	 * Add a survey level manifest such as a csv file from an calculate attribute
	 */
	public static ManifestInfo addManifestFromCalculate(String calculate, String inputManifest) {
		
		ManifestInfo mi = new ManifestInfo();
		ArrayList<String> refQuestions = null;
		String manifestType = null;
		
		mi.manifest = inputManifest;
		mi.changed = false;
		
		// Check to see if this appearance references a manifest file
		if(calculate != null && calculate.toLowerCase().trim().contains("pulldata(")) {
			
			// Yes it references a manifest
			// Get all the pulldata functions from this calculate
			
			int idx1 = calculate.indexOf("pulldata");
			while(idx1 >= 0) {
				idx1 = calculate.indexOf('(', idx1);
				int idx2 = calculate.indexOf(')', idx1);
				if(idx1 >= 0 && idx2 > idx1) {
					String criteriaString = calculate.substring(idx1 + 1, idx2);
					
					String criteria [] = criteriaString.split(",");
					
					if(criteria.length > 0) {
						
						if(criteria[0] != null && criteria[0].length() > 2) {	// allow for quotes
							String filename = criteria[0].trim();
							filename = filename.substring(1, filename.length() -1);
							
							if(filename.startsWith("linked_s")) {	// Linked survey
								log.info("We have found a manifest link to " + filename);
								refQuestions = getRefQuestionsSearch(criteria);
								manifestType = "linked";
								System.out.println("RefQuestions: " + refQuestions.toString());
							} else {
								filename += ".csv";
								manifestType = "csv";
								log.info("We have found a manifest file " + filename);
							}
							
							updateManifest(mi, filename, refQuestions, manifestType);
						}
					}
					idx1 = calculate.indexOf("pulldata(", idx2);
				}				
			}
		} 
		
		return mi;
			
	}
	
	/*
	 * Update the manifest
	 */
	private static void updateManifest(ManifestInfo mi, String filename, ArrayList<String> refQuestions, String manifestType) {
		
		String inputManifest = mi.manifest;
		Gson gson = new GsonBuilder().disableHtmlEscaping().create();
		
		ArrayList<String> mArray = null;
		if(inputManifest == null) {
			mArray = new ArrayList<String>();
		} else {
			Type type = new TypeToken<ArrayList<String>>(){}.getType();
			mArray = gson.fromJson(inputManifest, type);	
		}
		if(!mArray.contains(filename)) {
			mArray.add(filename);
			mi.changed = true;
			mi.filename = filename;
		}

		mi.manifest = gson.toJson(mArray);
		
	}
	
	/*
	 * Get the questions referenced by a search function in a linked survey
	 */
	private static ArrayList<String> getRefQuestionsSearch(String [] params) {
		ArrayList<String> refQuestions = new ArrayList<String> ();
		String param = null;
		
		/*
		 * The number of parameters can vary from 1 to 6
		 * params[0] is the primary function: "search" 
		 * params[1] is the matching function, ie 'matches'
		 * params[2] is a question name  (Get this one)
		 * params[3] is a value for the question in param[2]
		 * params[4] is the filter column name (Get this one)
		 * params[5] is the filter value
		 * 
		 */
		if(params.length > 2) {
			param = params[2].trim();
			param = param.substring(1, param.length() -1);		// Remove quotes
			refQuestions.add(param);
		}
		if(params.length > 4) {
			param = params[4].trim();
			param = param.substring(1, param.length() -1);		// Remove quotes
			refQuestions.add(param);
		}
		return refQuestions;
	}
	
	/*
	 * Get the questions referenced by a pulldata function in a linked survey
	 */
	private static ArrayList<String> getRefQuestionsPulldata(String [] params) {
		ArrayList<String> refQuestions = new ArrayList<String> ();
		String param = null;
		
		/*
		 * The number of parameters are 4
		 * params[0] is the primary function "pulldata"
		 * params[1] is the data column (Get this one)
		 * params[2] is the key column  (Get this one)
		 * params[3] is the key value
		 * 
		 */
		if(params.length > 1) {
			param = params[1].trim();
			param = param.substring(1, param.length() -1);		// Remove quotes
			refQuestions.add(param);
		}
		if(params.length > 2) {
			param = params[2].trim();
			param = param.substring(1, param.length() -1);		// Remove quotes
			refQuestions.add(param);
		}
		return refQuestions;
	}

	/*
	 * Get the main results table for a survey if it exists
	 */
	public static String getMainResultsTable(Connection sd, Connection conn, int sId) {
		String table = null;
		
		String sqlGetMainForm = "select table_name from form where s_id = ? and parentform = 0;";
		PreparedStatement pstmt = null;
		
		try {
			
			pstmt = sd.prepareStatement(sqlGetMainForm);
			pstmt.setInt(1,sId);
			
			log.info("Getting main form: " + pstmt.toString() );
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				String table_name = rs.getString(1);
				if(tableExists(conn, table_name)) {
					table = table_name;
				}
			}
			
		} catch (Exception e) {
			log.log(Level.SEVERE, "Exception", e);
		} finally {
			
			try {if (pstmt != null) {pstmt.close();	}} catch (SQLException e) {	}
			
		}
		
		return table;
	}
	
	/*
	 * Check for the existence of a table
	 */
	public static boolean tableExists(Connection conn, String tableName) throws SQLException {
		
		String sqlTableExists = "select count(*) from information_schema.tables where table_name =?;";
		PreparedStatement pstmt = null;
		int count = 0;
		
		try {
			pstmt = conn.prepareStatement(sqlTableExists);
			pstmt.setString(1, tableName );
		
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				count = rs.getInt(1);
			}
		} finally {
			try {if (pstmt != null) {pstmt.close();	}} catch (SQLException e) {	}
		}
		return (count > 0);
	}
	
	/*
	 * Method to check for presence of the specified column
	 */
	public static boolean hasColumn(Connection cRel, String tablename, String columnName)  {
		
		boolean hasColumn = false;
		
		String sql = "select column_name " +
					"from information_schema.columns " +
					"where table_name = ? and column_name = ?;";
		
		PreparedStatement pstmt = null;

		try {
			pstmt = cRel.prepareStatement(sql);
			pstmt.setString(1, tablename);
			pstmt.setString(2, columnName);
			System.out.println("SQL: " + pstmt.toString());
			
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				hasColumn = true;
			}

		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {if (pstmt != null) {pstmt.close();}} catch (Exception e) {}
		}
		
		return hasColumn;
	}
	
	/*
	 * Get the table that contains a question name
	 *   If there is a duplicate question in a survey then throw an error
	 */
	public static String getTableForQuestion(Connection sd, int sId, String column_name) throws Exception {
		
		String sql = "select table_name from form f, question q "
				+ "where f.s_id = ? "
				+ "and f.f_id = q.f_id "
				+ "and q.column_name = ?;";
		
		PreparedStatement pstmt = null;
		int count = 0;
		String table = null;
		
		try {
			pstmt = sd.prepareStatement(sql);
			pstmt.setInt(1, sId );
			pstmt.setString(2, column_name );
		
			ResultSet rs = pstmt.executeQuery();
			while(rs.next()) {
				table = rs.getString(1);
				count++;
			}
			
			if(count == 0) {
				throw new Exception("Table containing data for " + column_name + " in survey " + sId + " not found");
			} else if (count > 1) {
				throw new Exception("Duplicate " + column_name + " found in survey " + sId);
			}
			
		} finally {
			try {if (pstmt != null) {pstmt.close();	}} catch (SQLException e) {	}
		}
		
		return table;
	}
	
	/*
	 * Get the details of the top level form
	 */
	public static Form getTopLevelForm(Connection sd, int sId) throws SQLException {
		
		Form f = new Form ();
		
		String sql = "select  "
				+ "f_id,"
				+ "table_name "
				+ "from form "
				+ "where s_id = ? "
				+ "and parentform = 0;";
		PreparedStatement pstmt = null;
		
		
		try {
			pstmt = sd.prepareStatement(sql);
			pstmt.setInt(1,  sId);
			
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				f.id = rs.getInt("f_id");
				f.tableName = rs.getString("table_name");
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) {pstmt.close();}} catch (SQLException e) {}
		}	
		
		return f;
		
	}
	
	/*
	 * Get the details of the provided form Id
	 */
	public static Form getForm(Connection sd, int sId, int fId) throws SQLException {
		
		Form f = new Form ();
		
		String sql = "select  "
				+ "f_id,"
				+ "table_name "
				+ "from form "
				+ "where s_id = ? "
				+ "and f_id = ?;";
		PreparedStatement pstmt = null;
		
		
		try {
			pstmt = sd.prepareStatement(sql);
			pstmt.setInt(1,  sId);
			pstmt.setInt(2,  fId);
			
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				f.id = rs.getInt("f_id");
				f.tableName = rs.getString("table_name");
			}
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmt != null) {pstmt.close();}} catch (SQLException e) {}
		}	
		
		return f;
		
	}
	
	/*
	 * Convert a location in well known text into latitude
	 */
	public static String wktToLatLng(String location, String axis) {
		String val = null;
		int idx;
		int idx2;
		String [] coords = null;
		
		if(location != null) {
			idx = location.indexOf('(');
			if(idx >= 0) {
				idx2 = location.lastIndexOf(')'); 
				if(idx2 >= 0) {
					System.out.println("location: " + location);
					location = location.substring(idx + 1, idx2);
					System.out.println("location2: " + location);
					coords = location.split(" ");
					
					if(coords.length > 1) {
						if(axis.equals("lng")) {
							val = coords[0];
						} else {
							val = coords[1];
						}
					}
				}
				
			}
		}
			
		return val;
	
	}
	
	/*
	 * Get the index in the language array for the provided language
	 */
	public static int getLanguageIdx(org.smap.sdal.model.Survey survey, String language) {
		int idx = 0;
		
		if(survey != null && survey.languages != null) {
			for(int i = 0; i < survey.languages.size(); i++) {
				if(survey.languages.get(i).name.equals(language)) {
					idx = i;
					break;
				}
			}
		}
		return idx;
	}
	

}
