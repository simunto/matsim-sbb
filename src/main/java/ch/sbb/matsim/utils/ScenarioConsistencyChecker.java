/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2020 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package ch.sbb.matsim.utils;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.config.variables.SBBActivities;
import ch.sbb.matsim.config.variables.SBBModes;
import ch.sbb.matsim.config.variables.Variables;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.StageActivityHandling;
import org.matsim.vehicles.Vehicle;

public class ScenarioConsistencyChecker {

	public static final Logger LOGGER = Logger.getLogger(ScenarioConsistencyChecker.class);

	public static void checkScenarioConsistency(Scenario scenario) {
		checkExogeneousShares(scenario);
		if (!(checkVehicles(scenario) && checkPlans(scenario) && checkIntermodalAttributesAtStops(scenario))) {
			throw new RuntimeException(" Error found while checking consistency of plans. Check log!");
		}
	}

	private static void checkExogeneousShares(Scenario scenario) {
		double sum = scenario.getPopulation().getPersons().size();
		Map<String, Integer> subpops = scenario.getPopulation().getPersons().values().stream().map(p -> PopulationUtils.getSubpopulation(p)).filter(Objects::nonNull)
				.collect(Collectors.toMap(s -> s, s -> 1, Integer::sum));
		LOGGER.info("Found the following subpopulations: " + subpops.keySet().toString());
		Map<String, Double> shares = Map.of("regular", 0.65, "freight_road", 0.25, "cb_road", 0.08, "cb_rail", 0.0067, "airport_road", 0.0033, "airport_rail", 0.0024);

		LOGGER.info("Persons per Subpopulation");
		LOGGER.info("Subpopulation\tAbsolute\tShare\tShare in MOBi 3.1");
		for (Entry<String, Integer> e : subpops.entrySet()) {
			Double m31share = shares.get(e.getKey());
			LOGGER.info(e.getKey() + "\t" + e.getValue() + "\t" + e.getValue() / sum + "\t" + m31share);
		}

	}

	private static boolean checkPlans(Scenario scenario) {
		boolean checkPassed = true;
		Set<Person> regularPopulation = scenario.getPopulation().getPersons().values().stream()
				.filter(p -> PopulationUtils.getSubpopulation(p).equals(Variables.REGULAR)).collect(Collectors.toSet());
		if (regularPopulation.size() == 0) {
			LOGGER.error("No agent in subpopulation" + Variables.REGULAR + " found. ");
			checkPassed = false;

		}
		Set<String> activitytypes = regularPopulation.stream()
				.flatMap(person -> TripStructureUtils.getActivities(person.getSelectedPlan(), StageActivityHandling.ExcludeStageActivities).stream())
				.map(a -> a.getType().split("_")[0])
				.collect(Collectors.toSet());
		Set<String> permissibleActivityTypes = new HashSet<>(SBBActivities.abmActs2matsimActs.values());
		permissibleActivityTypes.add(Variables.OUTSIDE);
		if (!permissibleActivityTypes.containsAll(activitytypes)) {
			LOGGER.error("Detected unknown activity types: \n" + activitytypes + "\n Permissible Types: " + permissibleActivityTypes);
			checkPassed = false;
		}

		Set<String> modes = regularPopulation.stream()
				.flatMap(person -> TripStructureUtils.getLegs(person.getSelectedPlan()).stream())
				.map(a->a.getMode())
				.collect(Collectors.toSet());
		Set<String> permissiblemodes = new HashSet<>(SBBModes.mode2HierarchalNumber.keySet());
		permissiblemodes.add(Variables.OUTSIDE);
		if (!permissiblemodes.containsAll(modes)) {
			LOGGER.error("Detected unknown modes: \n" + modes + "\n Permissible Types: " + permissiblemodes);
			checkPassed = false;
		}
		for (Person  p : regularPopulation){
			var atts = p.getAttributes().getAsMap();
			for (var at : Variables.DEFAULT_PERSON_ATTRIBUTES){
				if (!atts.containsKey(at)) {
					LOGGER.error("Person " + p.getId() + " has no attribute " + at);
					checkPassed = false;
				}
			}
		if (!String.valueOf(p.getAttributes().getAttribute(Variables.CAR_AVAIL)).equals(Variables.CAR_AVAL_TRUE)){
			var usesCar = TripStructureUtils.getLegs(p.getSelectedPlan()).stream().anyMatch(leg -> leg.getMode().equals(SBBModes.CAR));
			if (usesCar) {
				LOGGER.error("Person " + p.getId() + " has no car available, but at least one car trip in initial plan");
				checkPassed = false;
			}
		}


		}
		//all agents (including exogenous demand)
		for (Person p : scenario.getPopulation().getPersons().values()){
			int legs = TripStructureUtils.getLegs(p.getSelectedPlan()).size();
			int acts = TripStructureUtils.getActivities(p.getSelectedPlan(), StageActivityHandling.StagesAsNormalActivities).size();
			if (legs + 1 != acts) {
				LOGGER.error("Person " + p.getId() + " has an inconsistent number of legs and activities in selected plan");
				LOGGER.error(p.getSelectedPlan());
				checkPassed = false;

			}
		}

		return checkPassed;
	}

	public static boolean checkVehicles(Scenario scenario) {
		Set<Id<Vehicle>> allvehicles = new HashSet<>();
		allvehicles.addAll(scenario.getVehicles().getVehicles().keySet());
		allvehicles.addAll(scenario.getTransitVehicles().getVehicles().keySet());
		if (allvehicles.size() != scenario.getVehicles().getVehicles().size() + scenario.getTransitVehicles().getVehicles().size()) {
			LOGGER.error("Some vehicle Ids exist both as transit vehicle Ids and cars.");
			return false;
		}
		return true;
	}

	public static boolean checkIntermodalAttributesAtStops(Scenario scenario) {
		SwissRailRaptorConfigGroup swissRailRaptorConfigGroup = ConfigUtils.addOrGetModule(scenario.getConfig(), SwissRailRaptorConfigGroup.class);
		boolean checkPassed = true;
		if (swissRailRaptorConfigGroup.isUseIntermodalAccessEgress()) {
			Set<String> intermodalModesAtt = swissRailRaptorConfigGroup.getIntermodalAccessEgressParameterSets().stream().map(s -> s.getStopFilterAttribute()).collect(Collectors.toSet());
			intermodalModesAtt.remove(null);
			for (String att : intermodalModesAtt) {
				int count = scenario.getTransitSchedule().getFacilities().values().stream().map(transitStopFacility -> transitStopFacility.getAttributes().getAttribute(att))
						.filter(Objects::nonNull)
						.mapToInt(a -> Integer.valueOf(a.toString()))
						.sum();

				if (count == 0) {
					checkPassed = false;
					LOGGER.error("No stop has a value defined for  " + att);
				} else {
					LOGGER.info("Found " + count + " stops with intermodal access attribute " + att);
				}
			}
		}
		return checkPassed;

	}

	//TODO: once we remove the intermodal attribute CSV, add this checker to the others.
	public static void checkIntermodalPopulationExists(Scenario scenario) {
		SwissRailRaptorConfigGroup swissRailRaptorConfigGroup = ConfigUtils.addOrGetModule(scenario.getConfig(), SwissRailRaptorConfigGroup.class);
		Set<String> modeAtts = swissRailRaptorConfigGroup.getIntermodalAccessEgressParameterSets().stream().map(s -> s.getPersonFilterAttribute()).filter(Objects::nonNull).collect(Collectors.toSet());
		for (String att : modeAtts) {
			int count = scenario.getPopulation().getPersons().values().stream().map(p -> p.getAttributes().getAttribute(att)).filter(Objects::nonNull).mapToInt(a -> Integer.parseInt(a.toString()))
					.sum();
			if (count == 0) {
				LOGGER.error("No person has a value defined for  " + att);

			} else {
				LOGGER.info("Found " + count + " persons with intermodal access attribute " + att);
			}
		}

	}
}
