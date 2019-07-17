# simtest.py - Test spacecraft measurement simulator.
# Copyright (C) 2018-2019 University of Texas
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.

import os
import sys
import time

if (len(sys.argv) < 3):
    print("Usage: python %s config_file output_file" % sys.argv[0])
    exit()

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from orbdetpy import simulateMeasurements

print("Simulation start : %s" % time.strftime("%Y-%m-%d %H:%M:%S"))

config = []

for i in range(1,len(sys.argv)-1):
	with open(sys.argv[i], "r") as fp:
		config.insert(i-1,fp.read())


output = simulateMeasurements(config)

with open(sys.argv[len(sys.argv)-1], "w") as fp:
    fp.write(output)

print("Simulation end   : %s" % time.strftime("%Y-%m-%d %H:%M:%S"))
