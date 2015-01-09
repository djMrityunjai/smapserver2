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

package org.smap.sdal.managers;

import java.io.File;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.smap.sdal.Utilities.MediaUtilities;
import org.smap.sdal.Utilities.UtilityMethods;
import org.smap.sdal.model.ManifestValue;
import org.smap.sdal.model.NotifyDetails;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;


public class TranslationManager {
	
	private static Logger log =
			 Logger.getLogger(TranslationManager.class.getName());

	private String manifestQuerySql = 
			" from translation t, survey s, users u, user_project up, project p" +
					" where u.id = up.u_id " +
					" and p.id = up.p_id " +
					" and s.p_id = up.p_id " +
					" and s.s_id = t.s_id " +
					" and (t.type = 'image' or t.type = 'video' or t.type = 'audio' or t.type = 'csv') " +
					" and u.ident = ? " +
					" and t.s_id = ?; ";
	
	public List<ManifestValue> getManifestBySurvey(Connection sd, 
			String user, 
			int surveyId,
			String basePath
			)	throws SQLException {
		
		HashMap<String, String> files = new HashMap<String, String> ();
		ArrayList<ManifestValue> manifests = new ArrayList<ManifestValue>();	// Results of request
		int oId = MediaUtilities.getOrganisationId(sd, user);
		
		String sqlQuestionLevel = "select t.text_id, t.type, t.value " +
				manifestQuerySql;
		PreparedStatement pstmtQuestionLevel = null;
		
		String sqlSurveyLevel = "select manifest from survey where s_id = ?; ";
		PreparedStatement pstmtSurveyLevel = null;
		
		try {
			
			/*
			 * Get Question and Option level manifests from the translation table
			 */
			pstmtQuestionLevel = sd.prepareStatement(sqlQuestionLevel);	 			
			pstmtQuestionLevel.setString(1, user);
			pstmtQuestionLevel.setInt(2, surveyId);
			ResultSet rs = pstmtQuestionLevel.executeQuery();
			
			while (rs.next()) {								
	
				ManifestValue m = new ManifestValue();
				m.sId = surveyId;
				m.text_id = rs.getString(1);
				m.type = rs.getString(2);
				m.value = rs.getString(3);
								
				if(m.value != null) {
					// Get file name from value (Just for legacy, new media should be stored as the file name only)
					int idx = m.value.lastIndexOf('/');	
					m.fileName = m.value.substring(idx + 1);					
					getFileUrl(m, surveyId, m.fileName, basePath, oId);		// Url will be null if file does not exist
					
					// Make sure we have not already added this file (Happens with multiple languages referencing the same file)
					if(files.get(m.fileName) == null) {
						files.put(m.fileName, m.fileName);
						manifests.add(m);
					}
				}
			} 
			
			/*
			 * Get Survey Level manifests from survey table
			 */
			pstmtSurveyLevel = sd.prepareStatement(sqlSurveyLevel);	 			
			pstmtSurveyLevel.setInt(1, surveyId);
			rs = pstmtSurveyLevel.executeQuery();
			if(rs.next()) {
				String manifestString = rs.getString(1);
				Gson gson = new GsonBuilder().setDateFormat("yyyy-MM-dd").create();
				Type type = new TypeToken<ArrayList<String>>(){}.getType();
				ArrayList<String> manifestList = new Gson().fromJson(manifestString, type);
				
				for(int i = 0; i < manifestList.size(); i++) {
					
					ManifestValue m = new ManifestValue();
					m.sId = surveyId;
					m.type = "csv";
					
					m.fileName = manifestList.get(i);
					getFileUrl(m, surveyId, m.fileName, basePath, oId);
					
					manifests.add(m);
				}
			}
			
			
		} catch (SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			if (pstmtQuestionLevel != null) { try {pstmtQuestionLevel.close();} catch (SQLException e) {}}
			if (pstmtSurveyLevel != null) { try {pstmtSurveyLevel.close();} catch (SQLException e) {}}
		}
		return manifests;
	}
	
	/*
	 * Returns true if the user can access the survey and that survey has a manifest
	 */
	public boolean hasManifest(Connection sd, 
			String user, 
			int surveyId
			)	throws SQLException {
		
		boolean hasManifest = false;
		
		/*
		 * Test for a question level manifest
		 */
		String sqlQuestionLevel = "select count(*) " +
				manifestQuerySql;
		
		String sqlSurveyLevel = "select count(*) from survey where s_id = ? and manifest is not null";
		
		PreparedStatement pstmtQuestionLevel = null;
		PreparedStatement pstmtSurveyLevel = null;
		
		try {
			ResultSet resultSet = null;
			pstmtQuestionLevel = sd.prepareStatement(sqlQuestionLevel);	 			
			pstmtQuestionLevel.setString(1, user);
			pstmtQuestionLevel.setInt(2, surveyId);
			resultSet = pstmtQuestionLevel.executeQuery();
			
			if(resultSet.next()) {
				if(resultSet.getInt(1) > 0) {
					hasManifest = true;
				}
			}
			
			if(!hasManifest) {
				/*
				 * Test for a survey level manifest
				 */
				pstmtSurveyLevel = sd.prepareStatement(sqlSurveyLevel);
				pstmtSurveyLevel.setInt(1, surveyId);
				resultSet = pstmtSurveyLevel.executeQuery();
				
				if(resultSet.next()) {
					if(resultSet.getInt(1) > 0) {
						hasManifest = true;
					}
				}
			}
		} catch (SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			if (pstmtQuestionLevel != null) { try {pstmtQuestionLevel.close();} catch (SQLException e) {}}
			if (pstmtSurveyLevel != null) { try {pstmtSurveyLevel.close();} catch (SQLException e) {}}
		}
		
		return hasManifest;	
	}
	
	/*
	 * Get the partial (URL) of the file and its file path or null if the file does not exist
	 */
	public void getFileUrl(ManifestValue manifest, int sId, String fileName, String basePath, int oId) {
		
		String url = null;
		File file = null;
		
		// First try the survey level
		url = "/media/" + sId + "/" + fileName;		
		file = new File(basePath + url);
		if(file.exists()) {
			manifest.url = url;
			manifest.filePath = basePath + url;
		} else {
		
			// Second try the organisation level
			url = "/media/organisation/" + oId + "/" + fileName;		
			file = new File(basePath + url);
			if(file.exists()) {
				manifest.url = url;
				manifest.filePath = basePath + url;
			}		
		}
	}
}
