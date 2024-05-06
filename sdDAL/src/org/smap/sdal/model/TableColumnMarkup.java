package org.smap.sdal.model;

/*
 * TableColumn class used to manage data shown in a table
 */
public class TableColumnMarkup {
	public String value;
	public String classes;

	public TableColumnMarkup(String v, String c) {
		value = v;
		classes = c;
	}
}
