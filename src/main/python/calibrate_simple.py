#!/usr/bin/env python
# -*- coding: utf-8 -*-

from matsim.calibration import create_calibration, ASCCalibrator, utils

# %%

modes = ["walk", "car", "ride", "pt", "bike"]
fixed_mode = "walk"
initial = {
    "bike": -0.141210,
    "pt": 0.0781477780346438,
    "car": 0.871977390743304,
    "ride": -2.22873502992
}

# FIXME: Adjust
target = {
    "walk": 0.1,
    "bike": 0.1,
    "pt": 0.1,
    "car": 0.1,
    "ride": 0.1
}


def filter_modes(df):
    return df[df.main_mode.isin(modes)]


def cli(jvm_args, jar, config, params_path, run_dir, trial_number, run_args):
    return "java %s -jar %s %s %s --config:controler.runId %03d --params %s %s" % (
        jvm_args, jar, config, run_dir, trial_number, params_path, run_args
    )


study, obj = create_calibration("calib",
                                ASCCalibrator(modes, initial, target,
                                              lr=utils.linear_scheduler(start=0.5, interval=10)),
                                "../../../sim/0.01-ref-2020/matsim-sbb-4.0.5-jar-with-dependencies.jar",
                                "../../../sim/0.01-ref-2020/config_scoring_parsed.xml",
                                args="",
                                jvm_args="-Xmx12G -Xmx12G -XX:+AlwaysPreTouch -XX:+UseParallelGC",
                                custom_cli=cli,
                                transform_trips=filter_modes,
                                chain_runs=True, debug=True)

# %%

study.optimize(obj, 5)
