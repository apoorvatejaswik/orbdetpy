Introduction
------------

This is orbdetpy, a library of Python and Java routines for orbit
determination. It is a thin Python wrapper for our estimation tools
and Orekit, which are both written in Java. 

Features
--------

The force model for orbit propagation currently includes:

1. EGM96 gravity field up to degree and order 360.
2. Earth solid tides due to the influence of the Sun and Moon.
3. FES 2004 ocean tide model up to degree and order 100.
4. The NRL MSISE-00 and simple exponential models for atmospheric drag.
5. Solar radiation pressure.
6. Third body perturbations from the Sun and Moon.

The measurement model supports range, range-rate, angles, and inertial
Cartesian coordinates. Filtering is done using Orekit's Extended Kalman
Filter or our custom Unscented Kalman Filter. Dynamic Model Compensation
(DMC) can be used with either filter to estimate additional perturbing
acclerations that result from unmodeled dynamics, maneuvers etc.

You can either use your own measurements or simulate observations using
the simulateMeasurements() function.

Installation
------------

1. Install the Java Development Kit 8+ (1.8+) from <http://openjdk.java.net/install/index.html>.
   Set the JAVA_HOME environment variable to point to your JDK installation.
2. Install Python 3.6+ and run "pip install orbdetpy" to install orbdetpy 
   and other package dependencies.
3. Source code, example programs and data files can be downloaded from 
   <https://github.com/ut-astria/orbdetpy>.

The orbdetpy/lib folder contains JAR files for the following libraries,
which are imported by orbdetpy automatically.

1. <https://github.com/google/gson>
2. <https://hipparchus.org> 
3. <https://www.orekit.org>

The astrodynamics data in orbdetpy/data must be updated periodically
using examples/update_data.py.

Examples
--------

The following example programs can be found in the 'examples' folder.
These examples use the Python wrapper interface but calling the
underlying Java implementation directly is straightforward.

1. testsim.py : Demonstrates the measurement simulator. Note that
   maneuvers can be incorporated into the force model during simulation.

2. plotsim.py : Plots the results of simulations created using testsim.py.

3. testodet.py : Demonstrates orbit determination in orbdetpy.

4. plotodet.py : Plots the results of fitting orbits using testodet.py.

5. run_tests.py : Run all the use cases under examples/data. Simulated
   measurements, orbit fits, differences between simulated truth versus
   estimates, and 3-sigma of estimated covariances will be written to
   output/ sub-folders.

orbdetpy uses JSON files to store settings, measurements and estimation
results. The files in examples/data show how to configure measurement
simulation and orbit determination using radar or telescope data. The
file docs/file_formats.md documents the structure of the JSON files.

The following are some typical use cases. It is assumed that the current
working directory is examples/data.

1. Simulate state vectors and radar measurements:

   python ../testsim.py radar_sim_cfg.json sim_data.json

   This will run the simulation configured in radar_sim_cfg.json and
   write simulated output to sim_data.json.

2. Plot simulation results:

   python ../plotsim.py radar_sim_cfg.json sim_data.json

   This will plot the simulated data generated in (1).

3. Run OD on simulated radar data:

   python ../testodet.py radar_od_cfg.json sim_data.json od_output.json

   This will run OD on the simulated radar data generated in (1)
   using the OD configuration in radar_od_cfg.json and write OD
   output to od_output.json.

4. Plot OD results:

   python ../plotodet.py radar_od_cfg.json sim_data.json od_output.json

   This will plot the OD results from (3).

Known Issues
------------

1. Java "Out of heap space" errors:

   The Java Virtual Machine with default settings may run out of heap
   space during long term simulations or orbit fits. The workaround
   is to add the following to the top of your Python code, before
   orbdetpy is imported. The value following "-Xmx" is the maximum
   heap size you wish to assign to Java; "G" stands for gigabytes.

   import jnius_config

   jnius_config.add_options("-Xmx2G")

Bug Reports
-----------

Comments, criticisms and bug reports are very welcome and may be sent to
the package maintainer by email or the project's website.

Shiva Iyer <shiva.iyer AT utexas DOT edu>