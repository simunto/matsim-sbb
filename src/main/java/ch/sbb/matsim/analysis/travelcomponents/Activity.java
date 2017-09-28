/*
 * Copyright (C) Schweizerische Bundesbahnen SBB, 2017.
 */

package ch.sbb.matsim.analysis.travelcomponents;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.config.Config;

public 	class Activity extends TravelComponent {
	private Id facility;
	private Coord coord;
	private String type;

	Activity(Config config){
	    super(config);
    }

	public String toString() {
		return String
				.format("ACT: type: %s start: %6.0f end: %6.0f dur: %6.0f x: %6.0f y: %6.0f facId: %s\n",
						getType(), getStartTime(), getEndTime(), getDuration(),
						getCoord().getX(), getCoord().getY(), getFacility().toString());
	}

	public Id getFacility() {
		return facility;
	}

	public void setFacility(Id facility) {
		this.facility = facility;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public Coord getCoord() {
		return coord;
	}

	public void setCoord(Coord coord) {
		this.coord = coord;
	}
}