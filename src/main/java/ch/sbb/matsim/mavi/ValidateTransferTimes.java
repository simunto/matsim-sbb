package ch.sbb.matsim.mavi;

import ch.sbb.matsim.csv.CSVWriter;
import java.io.IOException;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.MinimalTransferTimes;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;

public class ValidateTransferTimes {

	private final static Logger log = LogManager.getLogger(ValidateTransferTimes.class);

	public static void main(String[] args) {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(scenario).readFile(args[0]);
		TransitSchedule schedule = scenario.getTransitSchedule();

		try (CSVWriter mttWriter = new CSVWriter("", new String[]{"FROM_X", "FROM_Y", "TO_X", "TO_Y", "TRANSFERTIME"}, args[1])) {
			MinimalTransferTimes mtt = schedule.getMinimalTransferTimes();
			MinimalTransferTimes.MinimalTransferTimesIterator itr = mtt.iterator();
			while (itr.hasNext()) {
				itr.next();
				mttWriter.set("FROM_X", Double.toString(schedule.getFacilities().get(itr.getFromStopId()).getCoord().getX()));
				mttWriter.set("FROM_Y", Double.toString(schedule.getFacilities().get(itr.getFromStopId()).getCoord().getY()));
				mttWriter.set("TO_X", Double.toString(schedule.getFacilities().get(itr.getToStopId()).getCoord().getX()));
				mttWriter.set("TO_Y", Double.toString(schedule.getFacilities().get(itr.getToStopId()).getCoord().getY()));
				mttWriter.set("TRANSFERTIME", Double.toString(itr.getSeconds()));
				mttWriter.writeRow();
			}
		} catch (IOException e) {
			log.error("Could not write minimal transfer times. " + e.getMessage(), e);
		}
	}
}
