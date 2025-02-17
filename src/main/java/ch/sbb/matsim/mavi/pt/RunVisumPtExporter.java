/*
 * Copyright (C) Schweizerische Bundesbahnen SBB, 2017.
 */

package ch.sbb.matsim.mavi.pt;

import ch.sbb.matsim.config.variables.Filenames;
import ch.sbb.matsim.mavi.PolylinesCreator;
import ch.sbb.matsim.mavi.visum.Visum;
import ch.sbb.matsim.preparation.MobiTransitScheduleVerifiyer;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.MatsimVehicleWriter;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/***
 *
 * @author pmanser / SBB
 *
 * IMPORTANT:
 * Download the JACOB library version 1.18 and
 * set path to the library in the VM Options (e.g. -Djava.library.path="C:\Users\u225744\Downloads\jacob-1.18\jacob-1.18")
 *
 */

public class RunVisumPtExporter {

	private static final Logger log = LogManager.getLogger(RunVisumPtExporter.class);

	private static final String TRANSITSCHEDULE_OUT = "transitSchedule.xml.gz";
	private static final String TRANSITVEHICLES_OUT = "transitVehicles.xml.gz";

	public static void main(String[] args) throws IOException {
		new RunVisumPtExporter().run(args[0]);
	}

	private static void cleanStops(TransitSchedule schedule) {
		Set<Id<TransitStopFacility>> stopsToKeep = new HashSet<>();
		for (TransitLine line : schedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				for (TransitRouteStop stops : route.getStops()) {
					if (stops != null) {
						if (stops.getStopFacility() != null) {
							stopsToKeep.add(stops.getStopFacility().getId());
						} else {
							log.warn("A stop facility on route " + route.getId() + "on line" + line.getId() + " is null");
						}
					} else {
						log.warn("stop is null");
					}
				}
			}
		}
		Set<Id<TransitStopFacility>> stopsToRemove = schedule.getFacilities().keySet().stream().
				filter(stopId -> !stopsToKeep.contains(stopId)).
				collect(Collectors.toSet());
		stopsToRemove.forEach(stopId -> schedule.removeStopFacility(schedule.getFacilities().get(stopId)));
		log.info("removed " + stopsToRemove.size() + " unused stop facilities.");

		MinimalTransferTimes mtt = schedule.getMinimalTransferTimes();
		MinimalTransferTimes.MinimalTransferTimesIterator itr = mtt.iterator();
		while (itr.hasNext()) {
			itr.next();
			if (!stopsToKeep.contains(itr.getFromStopId()) || !stopsToKeep.contains(itr.getToStopId())) {
				mtt.remove(itr.getFromStopId(), itr.getToStopId());
			}
		}
	}

	private static void createOutputPath(URL path) {
		File outputPath = new File(path.getPath());
		outputPath.mkdirs();

	}

	private static void writeFiles(Scenario scenario, String outputPath) {
		new NetworkWriter(scenario.getNetwork()).write(new File(outputPath, Filenames.PT_NETWORK).getPath());
		new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(new File(outputPath, TRANSITSCHEDULE_OUT).getPath());
		new MatsimVehicleWriter(scenario.getVehicles()).writeFile(new File(outputPath, TRANSITVEHICLES_OUT).getPath());
	}

	public void run(String configFile) throws IOException {
		Config config = ConfigUtils.loadConfig(configFile, new VisumPtExporterConfigGroup());
		VisumPtExporterConfigGroup exporterConfig = ConfigUtils.addOrGetModule(config, VisumPtExporterConfigGroup.class);

		// Start Visum and load version
		Visum visum = new Visum(exporterConfig.getVisumVersion());

		visum.loadVersion(exporterConfig.getPathToVisumURL(config.getContext()).getPath()
				.replace("////", "//")
				.replace("/", "\\"));

		if (exporterConfig.getAngebotName() != null) {
			visum.filterForAngebot(exporterConfig.getAngebotName());
		}

		// currently not supported: additional filter for time profiles if desired
		//if(exporterConfig.getTimeProfilFilterParams().size() != 0)
		//    visum.setTimeProfilFilter(exporterConfig.getTimeProfilFilterConditions());

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());

		// load stops into scenario
		VisumStopExporter stops = new VisumStopExporter(scenario);
		stops.loadStopPoints(visum, exporterConfig);

		// load minimal transfer times into scenario
		new MTTExporter(scenario).integrateMinTransferTimes(visum, stops.getStopAreasToStopPoints());

		// load transit lines
		TimeProfileExporter tpe = new TimeProfileExporter(scenario);
		tpe.createTransitLines(visum, exporterConfig);
		// reduce the size of the network and the schedule by taking necessary things only.
		cleanStops(scenario.getTransitSchedule());
		new ScheduleCondenser(tpe.linkToVisumSequence, scenario.getTransitSchedule(), scenario.getNetwork()).condenseSchedule();
		ScheduleCondenser.cleanNetwork(scenario.getTransitSchedule(), scenario.getNetwork());

		// write outputs
		createOutputPath(exporterConfig.getOutputPathURL(config.getContext()));
		tpe.writeLinkSequence(exporterConfig.getOutputPathURL(config.getContext()).getPath(), scenario.getNetwork());
		writeFiles(scenario, exporterConfig.getOutputPathURL(config.getContext()).getPath());
		MobiTransitScheduleVerifiyer.verifyTransitSchedule(scenario.getTransitSchedule());

		// write polyline file
		new PolylinesCreator().runPt(scenario.getNetwork(), visum, tpe.linkToVisumSequence, exporterConfig.getOutputPath());
	}


}