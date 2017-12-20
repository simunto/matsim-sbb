/*
 * Copyright (C) Schweizerische Bundesbahnen SBB, 2017.
 */

package ch.sbb.matsim.routing.pt.raptor;

import org.matsim.core.config.Config;
import org.matsim.pt.router.TransitRouter;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * @author mrieser / SBB
 */
public class SwissRailRaptorFactory implements Provider<TransitRouter> {

    private SwissRailRaptorData data = null;
    private final TransitSchedule schedule;
    private final Config config;
    private final RaptorConfig raptorConfig;

    @Inject
    public SwissRailRaptorFactory(final TransitSchedule schedule, final Config config, final RaptorConfig raptorConfig) {
        this.schedule = schedule;
        this.config = config;
        this.raptorConfig = raptorConfig;
    }

    @Override
    public TransitRouter get() {
        SwissRailRaptorData data = getData();
        return new SwissRailRaptor(data, this.raptorConfig);
    }

    private SwissRailRaptorData getData() {
        if (this.data == null) {
            this.data = prepareData();
        }
        return this.data;
    }

    synchronized private SwissRailRaptorData prepareData() {
        if (this.data != null) {
            // due to multithreading / race conditions, this could still happen.
            // prevent doing the work twice.
            return this.data;
        }
        this.data = SwissRailRaptorData.create(this.schedule, this.config);
        return this.data;
    }

}
