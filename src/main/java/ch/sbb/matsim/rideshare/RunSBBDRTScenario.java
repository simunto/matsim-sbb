package ch.sbb.matsim.rideshare;

import ch.sbb.matsim.RunSBB;
import ch.sbb.matsim.rideshare.analysis.SBBDRTAnalysisModule;
import ch.sbb.matsim.rideshare.utils.RideshareAwareIntermodalMainModeIdentifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.scenario.ScenarioUtils;

public class RunSBBDRTScenario {

	private final static Logger log = LogManager.getLogger(RunSBBDRTScenario.class);

	public static void main(String[] args) {
		System.setProperty("matsim.preferLocalDtds", "true");
		final String configFile = args[0];
		log.info(configFile);
		Config config = ConfigUtils.loadConfig(configFile, getSBBAndDrtConfigGroups());
		if (args.length > 1) {
			config.controler().setOutputDirectory(args[1]);
		}
		prepareDrtConfig(config);
		Scenario scenario = DrtControlerCreator.createScenarioWithDrtRouteFactory(config);
		ScenarioUtils.loadScenario(scenario);
		RunSBB.addSBBDefaultScenarioModules(scenario);
		Controler controler = new Controler(scenario);
		RunSBB.addSBBDefaultControlerModules(controler);
		prepareDrtControler(controler);
		controler.run();
	}

	public static ConfigGroup[] getSBBAndDrtConfigGroups() {
		List<ConfigGroup> configGroupList = new ArrayList<>();
		configGroupList.addAll(Arrays.asList(RunSBB.getSbbDefaultConfigGroups()));
		configGroupList.add(new MultiModeDrtConfigGroup());
		configGroupList.add(new DvrpConfigGroup());

		return configGroupList.toArray(new ConfigGroup[configGroupList.size()]);
	}

	public static void prepareDrtConfig(Config config) {
		RunSBB.adjustMobiConfig(config);
		DrtConfigs.adjustMultiModeDrtConfig(MultiModeDrtConfigGroup.get(config), config.planCalcScore(), config.plansCalcRoute());
		config.checkConsistency();
	}

	public static void prepareDrtControler(Controler controler) {
		controler.addOverridingModule(new MultiModeDrtModule());
		controler.addOverridingModule(new DvrpModule());
		controler.configureQSimComponents(DvrpQSimComponents.activateAllModes(MultiModeDrtConfigGroup.get(controler.getConfig())));
		controler.addOverridingModule(new SBBDRTAnalysisModule());
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bind(MainModeIdentifier.class).to(RideshareAwareIntermodalMainModeIdentifier.class);
			}
		});
	}

}
