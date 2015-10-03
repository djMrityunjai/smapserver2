package org.smap.sdal.model;

import java.util.ArrayList;

import com.itextpdf.text.BaseColor;


public class DisplayItem {

	public int width;			// Width of entire cell item relative to enclosing item
	public int widthLabel = 5;	// Percentage width of label (If label is full width value appears below)
	public String value;
	public double valueHeight = -1.0;
	public String name;
	public String hint;
	public String text;
	public String type;
	public boolean isSet = false;
	public ArrayList<DisplayItem> choices = null;
	public BaseColor labelbg;
	
	public void debug() {
		System.out.println("======== Display Item:   width: " + width + "   value: " + value + " text: " + text + " : " + type  );
	}
}
