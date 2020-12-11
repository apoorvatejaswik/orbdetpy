/*
 * MultiTargetEstimation.java - JPDA, CAR-MHF implementation.
 * Copyright (C) 2019-2020 University of Texas
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.astria;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Collections;
import org.hipparchus.geometry.euclidean.threed.Rotation;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.hipparchus.linear.Array2DRowRealMatrix;
import org.hipparchus.linear.ArrayRealVector;
import org.hipparchus.linear.CholeskyDecomposition;
import org.hipparchus.linear.DiagonalMatrix;
import org.hipparchus.linear.MatrixUtils;
import org.hipparchus.linear.RealMatrix;
import org.hipparchus.linear.RealVector;
import org.hipparchus.ode.ODEStateAndDerivative;
import org.hipparchus.util.FastMath;
import org.hipparchus.distribution.continuous.ChiSquaredDistribution;
import org.orekit.attitudes.AttitudeProvider;
import org.orekit.estimation.measurements.ObservedMeasurement;
import org.orekit.estimation.measurements.Range;
import org.orekit.estimation.sequential.ConstantProcessNoise;
import org.orekit.estimation.sequential.CovarianceMatrixProvider;
import org.orekit.estimation.sequential.KalmanEstimation;
import org.orekit.estimation.sequential.KalmanEstimator;
import org.orekit.estimation.sequential.KalmanEstimatorBuilder;
import org.orekit.estimation.sequential.KalmanObserver;
import org.orekit.forces.ForceModel;
import org.orekit.forces.gravity.NewtonianAttraction;
import org.orekit.orbits.CartesianOrbit;
import org.orekit.orbits.PositionAngle;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.conversion.DormandPrince853IntegratorBuilder;
import org.orekit.propagation.integration.AbstractIntegratedPropagator;
import org.orekit.propagation.numerical.NumericalPropagator;
import org.orekit.propagation.sampling.OrekitFixedStepHandler;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;
import org.orekit.utils.ParameterDriver;
import org.orekit.utils.ParameterDriversList;
import org.orekit.utils.PVCoordinates;
import org.orekit.utils.TimeStampedPVCoordinates;

public final class MultiTargetEstimation
{
    public static class MultiTargetOutput
    {
	public ArrayList<ArrayList<Estimation.EstimationOutput>> estOutput;
	public ArrayList<ArrayList<Integer>> associatedObs;
	public ArrayList<Integer> unassociatedObs;
    }

    private Settings odCfg;
    private ArrayList<Settings> cfgList;
    
    private final Measurements odObs;

    private String[] measNames;
    private final boolean combinedMeas;

    private final AbsoluteDate epoch;
    private final AbsoluteDate propEnd;
    private final AbsoluteDate stepHandlerStart;
    private final AbsoluteDate stepHandlerEnd;
    private MultiTargetOutput multiOutput;

    private ArrayList<MultiTargetFilter.measurementObject> rawMeasurements;
    public ArrayList<MultiTargetFilter.measurementObject> unassociatedMeasurements;

    public MultiTargetEstimation(ArrayList<Settings> cfgList, ArrayList<Measurements> obsList)
    {
	this.cfgList = cfgList;
	this.odCfg = cfgList.get(0);
	this.odObs = obsList.get(0);

	odCfg.forces.add(0, new NewtonianAttraction(Constants.EGM96_EARTH_MU));
	
	measNames = this.odCfg.cfgMeasurements.keySet().toArray(new String[0]);
	Arrays.sort(measNames);
	if (measNames[0].equalsIgnoreCase("Declination"))
	    measNames = new String[]{"RightAscension", "Declination"};
	combinedMeas = !measNames[0].equalsIgnoreCase("Range") && !measNames[0].equalsIgnoreCase("RangeRate");

	epoch = DataManager.parseDateTime(this.odCfg.propStart);
	propEnd = DataManager.parseDateTime(this.odCfg.propEnd);

	if (this.odCfg.propStepHandlerStartTime != null)
	    stepHandlerStart = DataManager.parseDateTime(this.odCfg.propStepHandlerStartTime);
	else
	{
	    if (this.odCfg.propStep > 0.0)
		stepHandlerStart = epoch;
	    else
		stepHandlerStart = propEnd;
	}

	if (this.odCfg.propStepHandlerEndTime != null)
	    stepHandlerEnd = DataManager.parseDateTime(this.odCfg.propStepHandlerEndTime);
	else
	{
	    if (this.odCfg.propStep > 0.0)
		stepHandlerEnd = propEnd;
	    else
		stepHandlerEnd = epoch;
	}
    }

    public MultiTargetOutput multiTargetDetermineOrbit()
    {
	multiOutput = new MultiTargetOutput();
	new MultiTargetFilter().determineOrbit();
	return(multiOutput);
    }

    private class MultiTargetFilter
    {
	private void determineOrbit()
	{
	    HashMap<String, Integer> biasPos = new HashMap<String, Integer>();
	    if (odCfg.cfgStations != null)
	    {
		String[] stations = odCfg.cfgStations.keySet().toArray(new String[0]);
		for (int i = 0; i < stations.length; i++)
		{
		    for (int j = 0; j < measNames.length; j++)
		    {
			for (int k = 0; k < odCfg.parameters.size(); k++)
			{
			    String bias = new StringBuilder(stations[i]).append(measNames[j]).toString();
			    if (bias.equalsIgnoreCase(odCfg.parameters.get(k).name))
				biasPos.put(bias, k + 6);
			}
		    }
		}
	    }

	    int Rsize = 0;
	    for (String s: measNames)
		Rsize += odCfg.cfgMeasurements.get(s).error.length;
	    Array2DRowRealMatrix R = new Array2DRowRealMatrix(Rsize, Rsize);
	    for (int i = 0, j = 0; i < measNames.length; i++)
	    {
		Settings.Measurement jm = odCfg.cfgMeasurements.get(measNames[i]);
		for (int k = 0; k < jm.error.length; k++)
		{
		    R.setEntry(j, j, jm.error[k]*jm.error[k]);
		    j++;
		}
	    }
	    
	    int totalObjNum = cfgList.size();
	    boolean enableCAR = odCfg.estmEnableCARMHF;
	    boolean activateCAR = false;
	    final int numStates = odCfg.parameters.size() + 6;
	    final int numSigmas = 2*numStates;
	    final double weight = 0.5/numStates;
	    final double bound0 = stepHandlerStart.durationFrom(epoch);
	    final double bound1 = stepHandlerEnd.durationFrom(epoch);
	    AbsoluteDate tm = epoch;
	    final SpacecraftState[] ssta = new SpacecraftState[1];
	    final ManualPropagation propagator = new ManualPropagation(odCfg);

	    ArrayList<ArrayList<SingleObject>> residentSpaceObjects = new ArrayList<ArrayList<SingleObject>>();
	    ArrayList<SingleObject> promotedTracks = new ArrayList<SingleObject>();

	    rawMeasurements = new ArrayList<measurementObject>();
	    
	    for(int i = 0; i < odObs.measObjs.size(); i++)
	    {
	    	measurementObject temp = new measurementObject(); 
	    	temp.measObj = odObs.measObjs.get(i);
	    	temp.rawmeas = odObs.rawMeas[i];
	    	rawMeasurements.add(temp);
	    }
	    //Create object for each RSO
	    for(int objNum = 0; objNum < totalObjNum; objNum++)
	    {
	    	residentSpaceObjects.add(new ArrayList<SingleObject>());
	    	residentSpaceObjects.get(objNum).add(new SingleObject(cfgList.get(objNum), numStates, numSigmas, Rsize));
	    }

	    for(int smoothIter = 0; smoothIter < odCfg.estmSmootherIterations; smoothIter++)
	    {
    	unassociatedMeasurements = new ArrayList<measurementObject>(rawMeasurements);
		// Re-initialize ICs with previous smoothed results.
		
		
		if(smoothIter >= 1)
		{
			for(int objNum = 0; objNum < totalObjNum; objNum++)
			{
				SingleObject currSC = residentSpaceObjects.get(objNum).get(0);
								
				currSC.xhat = new ArrayRealVector(currSC.estOutput.get(0).estimatedState);
				currSC.xhatPrev = new ArrayRealVector(currSC.estOutput.get(0).estimatedState);
				currSC.P = new Array2DRowRealMatrix(currSC.estOutput.get(0).estimatedCovariance);
				currSC.dataAssociated = true;
						
				tm = new AbsoluteDate(currSC.estOutput.get(0).time, DataManager.getTimeScale("UTC"));
				currSC.smootherData = new ArrayList<smootherTimeStep>();

				currSC.estOutput = new ArrayList<Estimation.EstimationOutput>();
				currSC.associatedObsIndex = new ArrayList<Integer>();
				currSC.marginalEvents = new ArrayList<JPDALikelihoods>();

			}
		}


		int measIndex = -1;
		int additionalMeas = 0;
		boolean ODfinished = false;

		EstimationLoop:
	    while(!ODfinished)
	    {
		measIndex = measIndex + additionalMeas + 1;
		additionalMeas = 0;

		final AbsoluteDate t0 = tm;
		if (measIndex < rawMeasurements.size())
		{
		    tm = DataManager.parseDateTime(rawMeasurements.get(measIndex).rawmeas.time);
		    //Determine Number of measurements that need to be collected.
		    while(true)
		    {
			if (measIndex + additionalMeas + 1 < rawMeasurements.size() &&
				rawMeasurements.get(measIndex+additionalMeas).rawmeas.time.equals(rawMeasurements.get(measIndex+additionalMeas+1).rawmeas.time)
			    && rawMeasurements.get(measIndex+additionalMeas).rawmeas.station.equals(rawMeasurements.get(measIndex+additionalMeas+1).rawmeas.station))
			    additionalMeas++;	
			else
			    break;
		    }
		}
		else
		{
		    tm = propEnd;
		    ODfinished = true;
		}

		
		//Create new objects with CAR
		
		if(activateCAR == true && residentSpaceObjects.size() == 0)
		{
			for(int measNum = 0; measNum <= additionalMeas; measNum++)
			{
				
				ArrayList<SingleObject> tempHypotheses = new ArrayList<SingleObject>();
				
				ArrayList<Hypothesis> newHypotheses = GenerateHypotheses(rawMeasurements.get(measNum).rawmeas, Math.sqrt(R.getEntry(0,0)), 1e-5, Math.sqrt(R.getEntry(1,1)), 1e-5, numStates, numSigmas);
				  
				for(int hypNum = 0; hypNum < newHypotheses.size(); hypNum++)
				{
					Hypothesis temp = newHypotheses.get(hypNum);

					SingleObject tempState = new SingleObject(temp.xhat.toArray(), temp.P, numStates, numSigmas, Rsize, temp.weight);
					tempHypotheses.add(tempState);
				 	
				}
				
				residentSpaceObjects.add(tempHypotheses);
				totalObjNum++;
			}
			continue;
		}
		
		
		for(int objNum = 0; objNum < residentSpaceObjects.size(); objNum++)
		{
			for(int hypNum = 0; hypNum < residentSpaceObjects.get(objNum).size(); hypNum++)
			{
				residentSpaceObjects.get(objNum).get(hypNum).marginalEvents.clear();
			}
		}
		
		ArrayList<ArrayList<JPDALikelihoods>> jointEvents = new ArrayList<ArrayList<JPDALikelihoods>>();
		ArrayList<JPDALikelihoods> singleJointEvent = new ArrayList<JPDALikelihoods>();

		////////////////////////////////////////propagate loop////////////////////////////////////////
		//////////////////////////////////////////////////////////////////////////////////////////////
		System.out.println(tm);

		for(int objNum = 0; objNum < totalObjNum; objNum++)
		{
		for(int hypNum = 0; hypNum < residentSpaceObjects.get(objNum).size(); hypNum++)
		{
			double stepStart = t0.durationFrom(epoch), stepSum = 0.0;
			final double stepFinal = tm.durationFrom(epoch);

			SingleObject currSC = residentSpaceObjects.get(objNum).get(hypNum);

			final RealMatrix Ptemp = currSC.P.scalarMultiply(numStates);
			final RealMatrix sqrtP = new CholeskyDecomposition(
			    Ptemp.add(Ptemp.transpose()).scalarMultiply(0.5).add(currSC.psdCorr), 1E-6, 1E-16).getL();


		while (true)
		{
			if(currSC.dataAssociated == true)
			{
			    currSC.dataAssociated = false;
			    for (int i = 0; i < numStates; i++)
			    {
				currSC.sigma.setColumnVector(i, currSC.xhat.add(sqrtP.getColumnVector(i)));
				currSC.sigma.setColumnVector(numStates + i, currSC.xhat.subtract(sqrtP.getColumnVector(i)));
			    }

			    double[][] sigData = currSC.sigma.getData();
			    for (int j = 6; j < odCfg.parameters.size() + 6; j++)
			    {
				Settings.Parameter tempep = odCfg.parameters.get(j - 6);
				for (int i = 0; i < numSigmas; i++)
				    sigData[j][i] = FastMath.min(FastMath.max(sigData[j][i], tempep.min), tempep.max);
			    }
			    currSC.sigma.setSubMatrix(sigData, 0, 0);


			}
			else //If data is not associated, keep same sigma points.
			{
			currSC.sigma.setSubMatrix(currSC.propSigma.getData(), 0, 0);
			}

		    double step = stepFinal - stepStart;
		    if (odCfg.propStep != 0.0 && (stepStart >= bound0 || stepFinal >= bound0) &&
			(stepStart <= bound1 || stepFinal <= bound1))
		    {
			if (odCfg.propStep > 0.0)
			    step = FastMath.min(step, odCfg.propStep);
			else
			    step = FastMath.max(step, odCfg.propStep);
		    }
		    stepSum += step;

		    if (FastMath.abs(step) > 1.0E-6)
			propagator.propagate(stepStart, currSC.sigma, stepStart + step, currSC.propSigma, false);
		    else
	    	currSC.propSigma.setSubMatrix(currSC.sigma.getData(), 0, 0);


		    currSC.xhatPrev = addColumns(currSC.propSigma).mapMultiplyToSelf(weight);
		    currSC.xhat = new ArrayRealVector(currSC.xhatPrev);
		    currSC.Pprop = odCfg.getProcessNoiseMatrix(stepSum);

		    for (int i = 0; i < numSigmas; i++)
		    {
			RealVector y = currSC.propSigma.getColumnVector(i).subtract(currSC.xhatPrev);
			currSC.Pprop = currSC.Pprop.add(y.outerProduct(y).scalarMultiply(weight));

		    }

		    if (ODfinished || (odCfg.propStep != 0.0 && stepStart + step >= bound0 && stepStart + step <= bound1))
		    {
			Estimation.EstimationOutput odout = new Estimation.EstimationOutput();
			odout.time = DataManager.getUTCString(new AbsoluteDate(epoch, stepStart + step));
			odout.estimatedState = currSC.xhatPrev.toArray();
			odout.propagatedCovariance = currSC.Pprop.getData();
			currSC.estOutput.add(odout);
		    }

		    stepStart += step;
		    if (FastMath.abs(step) < 1.0E-6 || FastMath.abs(stepFinal - stepStart) < 1.0E-6)
		    {
			//check to make sure not last time step
				if(!ODfinished)
				{
					JPDALikelihoods JPDAtemp = new JPDALikelihoods();
					JPDAtemp.psi = (1 - odCfg.estmDetectionProbability) * currSC.hypothesisWeight;
					JPDAtemp.object = objNum;
					JPDAtemp.measurement = 0;

					currSC.marginalEvents.add(JPDAtemp);
					//Compute predicted measurement, compare to each measurement					    
				    for(int measNum = 0; measNum <= additionalMeas; measNum++)
				    {
				    	RealVector rawMeas = updatePrep(currSC, tm, measIndex+measNum, numSigmas, propagator, biasPos);
					    RealVector yhatpre = addColumns(currSC.estimMeas).mapMultiplyToSelf(weight);
					    
					    RealMatrix Pyy = R.copy();
					    for (int i = 0; i < numSigmas; i++)
					    {
						RealVector y = currSC.estimMeas.getColumnVector(i).subtract(yhatpre);
						Pyy = Pyy.add(y.outerProduct(y).scalarMultiply(weight));
					    }

						RealVector Innov = rawMeas.subtract(yhatpre);

						RealMatrix MahalaTemp = MatrixUtils.createColumnRealMatrix(Innov.toArray());
						RealMatrix Mahalanobis = MahalaTemp.transpose().multiply(MatrixUtils.inverse(Pyy).multiply(MahalaTemp));
						System.out.println(Math.sqrt(Mahalanobis.getEntry(0,0)));
						if(odCfg.estmGatingThreshold > Math.sqrt(Mahalanobis.getEntry(0,0)))
						{
							JPDAtemp = new JPDALikelihoods();

							//JPDAtemp2.psi =  Math.exp(-JPDATemp.getEntry(0,0)/2) / (Math.sqrt(new LUDecomposition(currentObj.Pyy).getDeterminant() * Math.pow(2 * Math.PI, currentObj.Rsize)));
							JPDAtemp.psi = (1 - new ChiSquaredDistribution(Rsize).cumulativeProbability(Mahalanobis.getEntry(0,0))) * currSC.hypothesisWeight;
							JPDAtemp.object = objNum;
							JPDAtemp.measurement = measNum+1;
							currSC.marginalEvents.add(JPDAtemp);
						}
				    }
				}


			break;

		    }
		}
		}
		}

		if (ODfinished)
		    break EstimationLoop;

		////////////////////////////////////////JPDA////////////////////////////////////////			
		////////////////////////////////////////////////////////////////////////////////////
		double[][] sumJPDALikelihoods = new double[totalObjNum][additionalMeas+2]; // +1 to account for measurement 0 case

		for(int objNum = 0; objNum < totalObjNum; objNum++)
		{
		for(int hypNum = 0; hypNum < residentSpaceObjects.get(objNum).size(); hypNum++)
		{
		    SingleObject currSC = residentSpaceObjects.get(objNum).get(hypNum);
		    for(int eventCounter = 0; eventCounter < currSC.marginalEvents.size(); eventCounter++)
		    {
		    	sumJPDALikelihoods[objNum][currSC.marginalEvents.get(eventCounter).measurement] += currSC.marginalEvents.get(eventCounter).psi;
		    }
		}
		}

		jointEvents = JPDAJointEvents(jointEvents, sumJPDALikelihoods, singleJointEvent, 0);

		
		double[] JPDAProbability = new double[jointEvents.size()];
		Arrays.fill(JPDAProbability, 1);

		for(int i = 0; i<jointEvents.size(); i++)
		{
			for(int j = 0; j<jointEvents.get(i).size(); j++)
			{
				//skip cases where  only one marginal event to choose from. This is important for when Pd = 1, and an object does not 
				//have an associated observation. This is detected by checking if an object has a probability of association of 0 for all measurements (including 0).
				//If this test is not checked then all joint probabilities will be 0

				double rowSum = 0;

				for(int measNum = 0; measNum < sumJPDALikelihoods[j].length; measNum++)
				{
					rowSum += sumJPDALikelihoods[j][measNum];
				}
				if(rowSum > 0)
				{
					JPDAProbability[i] = JPDAProbability[i] * jointEvents.get(i).get(j).psi;
				}
			}	
		}

		double JPDAsum = Arrays.stream(JPDAProbability).sum();

		for(int i = 0; i<jointEvents.size(); i++)
		{
			JPDAProbability[i] = JPDAProbability[i] / JPDAsum;
		}	

		//identify max probability to see if measurments has been associated
		int maxProbIndex = 0;

		for(int i=1;i < JPDAProbability.length;i++)
		{
		    if(JPDAProbability[i] > JPDAProbability[maxProbIndex])
			maxProbIndex = i;
		}


		for(int i = 0; i < jointEvents.get(maxProbIndex).size(); i++)
		{	
			if(jointEvents.get(maxProbIndex).get(i).measurement != 0)
			{
			    int objNum = jointEvents.get(maxProbIndex).get(i).object;
			    int measNum = measIndex + jointEvents.get(maxProbIndex).get(i).measurement - 1;
			    //remove from unassociated
			    unassociatedMeasurements.remove(rawMeasurements.get(measIndex + jointEvents.get(maxProbIndex).get(i).measurement - 1));

			    //Save Index
			    residentSpaceObjects.get(objNum).get(0).associatedObsIndex.add(measNum);

			    for(int j = 0; j < residentSpaceObjects.get(objNum).size(); j++)
			    residentSpaceObjects.get(objNum).get(j).dataAssociated = true;
		    	//indicate all hypotheses for a given object have been associated.
			}
		}

		//Combine Hypotheses into Events

		double[][] beta_sat_meas = new double[totalObjNum][additionalMeas+2]; // +1 to account for measurement 0 case			

		//compute Beta based on the object & measurement pair

		for(int i = 0; i < jointEvents.size(); i++)
		{
			for(int j = 0; j < jointEvents.get(i).size(); j++)
			{
				beta_sat_meas[jointEvents.get(i).get(j).object][jointEvents.get(i).get(j).measurement] += JPDAProbability[i];
			}	
		}		


		////////////////////////////////////////Update////////////////////////////////////////			
		//////////////////////////////////////////////////////////////////////////////////////
		for(int objNum = 0; objNum < totalObjNum; objNum++)
		{
		for(int hypNum = 0; hypNum < residentSpaceObjects.get(objNum).size(); hypNum++)
		{
			
			SingleObject currSC = residentSpaceObjects.get(objNum).get(hypNum);


			double Beta = 0;
			RealVector Betav = new ArrayRealVector(Rsize);
			RealMatrix Betavvt = new Array2DRowRealMatrix(Rsize, Rsize);

			//update hypothesis weight
			if(residentSpaceObjects.get(objNum).size() > 1)
			{
				double hypWeight = 0;			

				for(int counter = 0; counter < currSC.marginalEvents.size(); counter++)
				{
					int measNum = currSC.marginalEvents.get(counter).measurement;

					hypWeight += beta_sat_meas[objNum][measNum] / sumJPDALikelihoods[objNum][measNum] * currSC.marginalEvents.get(counter).psi;
				}

				currSC.hypothesisWeight = hypWeight;

			}

			for(int i = 0; i < currSC.marginalEvents.size(); i++)
			{
				if(currSC.marginalEvents.get(i).measurement > 0)
				{
				RealVector rawMeas = updatePrep(currSC, tm, measIndex+currSC.marginalEvents.get(i).measurement-1, numSigmas, propagator, biasPos);

			    RealVector yhatpre = addColumns(currSC.estimMeas).mapMultiplyToSelf(weight);

				RealVector Innov = quadCheck(rawMeas, yhatpre);

			    double BetaTemp = 0;

			    //This if statement is necessary if sumJPDALikelihoods is 0. This can only happen when JointEvents.get(i).get(j).Psi ==0 for all j. Betatemp now defaults to
			    //0, and if JointEvents.get(i).get(j).Psi != 0, then it updates. If JointEvents.get(i).get(j).Psi == 0, then Betatemp should be 0 regardless
			    //This check avoids a 0/0 case.

			    if(currSC.marginalEvents.get(i).psi != 0)
			    {
				    BetaTemp = beta_sat_meas[objNum][currSC.marginalEvents.get(i).measurement] / sumJPDALikelihoods[objNum][currSC.marginalEvents.get(i).measurement] 
							* currSC.marginalEvents.get(i).psi / currSC.hypothesisWeight;
			    }
				Beta = Beta + BetaTemp;
				Betav= Betav.add(Innov.mapMultiply(BetaTemp));
				Betavvt = Betavvt.add(Innov.outerProduct(Innov).scalarMultiply(BetaTemp));
				}
			}
			//Compute rawMeas and store needed data in currSC

		    RealMatrix Pyy = R.copy();
		    RealMatrix Pxy = new Array2DRowRealMatrix(numStates, Rsize);
		    RealVector yhatpre = addColumns(currSC.estimMeas).mapMultiplyToSelf(weight);
		    for (int i = 0; i < numSigmas; i++)
		    {
			RealVector y = currSC.estimMeas.getColumnVector(i).subtract(yhatpre);
			Pyy = Pyy.add(y.outerProduct(y).scalarMultiply(weight));
			Pxy = Pxy.add(currSC.propSigma.getColumnVector(i).subtract(currSC.xhatPrev).outerProduct(y).scalarMultiply(weight));
		    }

		    RealMatrix K = Pxy.multiply(MatrixUtils.inverse(Pyy));
			RealMatrix Ptilda = K.multiply((Betavvt.subtract(Betav.outerProduct(Betav))).multiply(K.transpose()));

		    currSC.xhat = new ArrayRealVector(currSC.xhatPrev.add(odCfg.parameterMatrix.multiply(K).operate(Betav)));
		    currSC.P = currSC.Pprop.subtract(odCfg.parameterMatrix.multiply(K.multiply(Pyy.multiply(K.transpose()))).subtract(Ptilda)); //Ptilda is subtracted due to need to include with parameter matrix.
		    
		    //For Estimated Parameters, return xhat values to max/min bounds. If far outside the bounds, can result in
		    //Singular Matrix when taking the inverse for smoothing.

		    for (int j = 6; j < odCfg.parameters.size() + 6; j++)
		    {
			Settings.Parameter tempep = odCfg.parameters.get(j - 6);
			currSC.xhat.setEntry(j, FastMath.min(FastMath.max(currSC.xhat.getEntry(j), tempep.min), tempep.max));
		    }

		    //If data associated, save data and compute smoother values.
		    if(currSC.dataAssociated == true)
		    {
		    
		    double[] pv = currSC.xhat.toArray();
		    ssta[0] = new SpacecraftState(new CartesianOrbit(new PVCoordinates(new Vector3D(pv[0], pv[1], pv[2]),
										       new Vector3D(pv[3], pv[4], pv[5])),
								     odCfg.propFrame, tm, Constants.EGM96_EARTH_MU),
						  propagator.getAttitude(tm, pv), odCfg.rsoMass);

		    Estimation.EstimationOutput odout = new Estimation.EstimationOutput();
		    odout.preFit = new HashMap<String, double[]>();
		    odout.postFit = new HashMap<String, double[]>();

		    if (combinedMeas || measNames.length == 1)
		    {
			for (int i = 0; i < measNames.length; i++)
			{
			    double[] fitv = rawMeasurements.get(measIndex).measObj.estimate(0, 0, ssta).getEstimatedValue();
			    if (measNames.length == 1)
			    {
				odout.preFit.put(measNames[i], yhatpre.toArray());
				odout.postFit.put(measNames[i], fitv);
			    }
			    else
			    {
				odout.preFit.put(measNames[i], new double[] {yhatpre.getEntry(i)});
				odout.postFit.put(measNames[i], new double[] {fitv[i]});
			    }
			}
		    }
		    else
		    {
			double[] fitv = rawMeasurements.get(measIndex*2).measObj.estimate(0, 0, ssta).getEstimatedValue();
			odout.preFit.put(measNames[0], new double[] {yhatpre.getEntry(0)});
			odout.postFit.put(measNames[0], fitv);
			fitv = rawMeasurements.get(measIndex*2 + 1).measObj.estimate(0, 0, ssta).getEstimatedValue();
			odout.preFit.put(measNames[1], new double[] {yhatpre.getEntry(1)});
			odout.postFit.put(measNames[1], fitv);
		    }

		    odout.time = rawMeasurements.get(measIndex).rawmeas.time;
		    odout.station = rawMeasurements.get(measIndex).rawmeas.station;
		    odout.estimatedState = pv;
		    odout.propagatedCovariance = currSC.Pprop.getData();
		    odout.innovationCovariance = Pyy.getData();
		    odout.estimatedCovariance = currSC.P.getData();
		    
		    currSC.estOutput.add(odout);

		    //generate post sigma points
		    final RealMatrix Ptemp = currSC.P.scalarMultiply(numStates);
			final RealMatrix sqrtP = new CholeskyDecomposition(
			    Ptemp.add(Ptemp.transpose()).scalarMultiply(0.5).add(currSC.psdCorr), 1E-6, 1E-16).getL();

			Array2DRowRealMatrix postSigma = new Array2DRowRealMatrix(numStates, numSigmas);

		    for (int i = 0; i < numStates; i++)
		    {
			postSigma.setColumnVector(i, currSC.xhat.add(sqrtP.getColumnVector(i)));
			postSigma.setColumnVector(numStates + i, currSC.xhat.subtract(sqrtP.getColumnVector(i)));
		    }

		    double[][] sigData = postSigma.getData();
		    for (int j = 6; j < odCfg.parameters.size() + 6; j++)
		    {
			Settings.Parameter tempep = odCfg.parameters.get(j - 6);
			for (int i = 0; i < numSigmas; i++)
			    sigData[j][i] = FastMath.min(FastMath.max(sigData[j][i], tempep.min), tempep.max);
		    }
		    postSigma.setSubMatrix(sigData, 0, 0);

		    // Store Smoother Data
		    smootherTimeStep smout = new smootherTimeStep();
		    smout.xpre = MatrixUtils.createColumnRealMatrix(currSC.xhatPrev.toArray());
		    smout.xpost = MatrixUtils.createColumnRealMatrix(currSC.xhat.toArray());
		    smout.Ppre = currSC.Pprop;
		    smout.Ppost = currSC.P;
		    smout.sigPre = MatrixUtils.createRealMatrix(currSC.propSigma.getData());
		    smout.sigPost = MatrixUtils.createRealMatrix(postSigma.getData());
		    smout.xstar = smout.xpost;
		    smout.Pstar = smout.Ppost;
		    smout.tmSmoother = tm;

		    if(combinedMeas || measNames.length == 1)
		    {
			smout.measObjsSmoother = rawMeasurements.get(measIndex).measObj;
		    }
		    else
		    {
			smout.measObjsSmoother = rawMeasurements.get(2 * measIndex).measObj;
			smout.measObjsSmootherNoComb = rawMeasurements.get(2* measIndex + 1).measObj;
		    }

		    currSC.smootherData.add(smout);
		    }

		}
	    }
		
		// Merge/Prune hypotheses
		for(int objNum = 0; objNum < totalObjNum; objNum++)
		{
			PruneHypotheses(residentSpaceObjects.get(objNum));
		}
		
		for(int objNum = 0; objNum < totalObjNum; objNum++)
		{
			MergeHypotheses(residentSpaceObjects.get(objNum));
		}
		
		// Normalize Weights
		for(int objNum = 0; objNum < totalObjNum; objNum++)
		{
			double sumWeights = 0;
			
			for(int hypNum = 0; hypNum < residentSpaceObjects.get(objNum).size(); hypNum++)
			{	
				sumWeights += residentSpaceObjects.get(objNum).get(hypNum).hypothesisWeight;
			}
			
			for(int hypNum = 0; hypNum < residentSpaceObjects.get(objNum).size(); hypNum++)
			{
				residentSpaceObjects.get(objNum).get(hypNum).hypothesisWeight /= sumWeights;
				
			}
		}
		
		
	    }


		//Smooth Data		
		for(int objNum = 0; objNum < totalObjNum; objNum++)
		{
		for(int hypNum = 0; hypNum < residentSpaceObjects.get(objNum).size(); hypNum++)
		{
		    SingleObject currSC = residentSpaceObjects.get(objNum).get(hypNum);
		    currSC.McReynoldsConsistencyPass = true;

		    int smSize = currSC.smootherData.size()-1;	

		    for(int i = 0; i < smSize; i++)
		    {
			smootherTimeStep smDatak1 = currSC.smootherData.get(smSize - i);
			smootherTimeStep smDatak = currSC.smootherData.get(smSize - i - 1);

			RealMatrix Csmoother = new Array2DRowRealMatrix(numStates,numStates);
			RealMatrix Asmoother = new Array2DRowRealMatrix(numStates,numStates);

			for(int j = 0; j < numSigmas; j++) 
			{
			    Csmoother = Csmoother.add(((smDatak.sigPost.getColumnMatrix(j).subtract(smDatak.xpost)).multiply( 
							   (smDatak1.sigPre.getColumnMatrix(j).subtract(smDatak1.xpre)).transpose())).scalarMultiply(weight));
			}


			Asmoother = Csmoother.multiply(MatrixUtils.inverse(smDatak1.Ppre));
			smDatak.xstar = smDatak.xpost.add(Asmoother.multiply(smDatak1.xstar.subtract(smDatak1.xpre)));
			smDatak.Pstar = smDatak.Ppost.add(Asmoother.multiply((smDatak1.Pstar.subtract(smDatak1.Ppre)).multiply(Asmoother.transpose())));
			currSC.smootherData.set(smSize - i - 1,smDatak);
		    }

		// compute McReynolds Consistency or merge all hypotheses into one hypothesis
	    if(residentSpaceObjects.get(objNum).size() == 1)
	    {
		double McReynoldsVal = -99999;

	    for(int i = 0; i < currSC.smootherData.size()-1;i++)
	    {
		smootherTimeStep smDatak1 = currSC.smootherData.get(smSize - i);
		smootherTimeStep smDatak = currSC.smootherData.get(smSize - i - 1);

		RealMatrix delx = smDatak.xpost.subtract(smDatak.xstar);	
		RealMatrix delP = smDatak.Ppost.subtract(smDatak.Pstar);

		for(int j = 0; j < 5; j++)
		{			
		    McReynoldsVal = Math.max(McReynoldsVal, Math.abs(delx.getEntry(j,0)) / Math.sqrt(Math.abs(delP.getEntry(j,j))));
		    if(Math.abs(delx.getEntry(j,0)) / Math.sqrt(Math.abs(delP.getEntry(j,j))) >= 3)
		    {
			currSC.McReynoldsConsistencyPass = false;
		    }
		}
	    }
	    System.out.println("MRV:" + McReynoldsVal);

		//store in results
	    SpacecraftState[] smSsta = new SpacecraftState[1];		

	    for(int j = 0; j < currSC.smootherData.size();j++)
	    {

		double[] pv = currSC.smootherData.get(j).xstar.getColumn(0);

		    tm = currSC.smootherData.get(j).tmSmoother;

		    smSsta[0] = new SpacecraftState(new CartesianOrbit(new PVCoordinates(new Vector3D(pv[0], pv[1], pv[2]),
										       new Vector3D(pv[3], pv[4], pv[5])),
								       odCfg.propFrame, tm, Constants.EGM96_EARTH_MU));

		//compute smoothed residuals

		    if (combinedMeas || measNames.length == 1)
		    {
			for (int i = 0; i < measNames.length; i++)
			{
			    double[] fitv = currSC.smootherData.get(j).measObjsSmoother.estimate(0, 0, smSsta).getEstimatedValue();
			    if (measNames.length == 1)
				currSC.estOutput.get(j).postFit.put(measNames[i], fitv);
			    else
				currSC.estOutput.get(j).postFit.put(measNames[i], new double[] {fitv[i]});
			}
		    }
		    else
		    {
				double[] fitv = currSC.smootherData.get(j).measObjsSmoother.estimate(0, 0, smSsta).getEstimatedValue();
				currSC.estOutput.get(j).postFit.put(measNames[0], fitv);
		    }

		    //store
		    currSC.estOutput.get(j).estimatedState = currSC.smootherData.get(j).xstar.getColumn(0);
		    currSC.estOutput.get(j).estimatedCovariance = currSC.smootherData.get(j).Pstar.getData();
		    

	    }
		}
	    else
	    {
	    	currSC.McReynoldsConsistencyPass = false;
	    	System.out.println("asdf");
	    }
	    
	    
	    
	    
		}
	    }
		
	    //Check McReynolds and potentially break out
		boolean promotedTrackReset = false;
		ArrayList<Integer> removeObs = new ArrayList<Integer>();
		int objNum = 0;
		while(objNum < totalObjNum)
		{
		    SingleObject currSC = residentSpaceObjects.get(objNum).get(0);

		    if(smoothIter >= 0 && currSC.McReynoldsConsistencyPass == true)
		    {
				promotedTrackReset = true;
			    //Compute new Innov Covar.

				for (measIndex = 0; measIndex < currSC.associatedObsIndex.size(); measIndex++)
				{
				    RealMatrix Ptemp = new Array2DRowRealMatrix(currSC.estOutput.get(measIndex).estimatedCovariance);
				    currSC.xhatPrev = new ArrayRealVector(currSC.estOutput.get(measIndex).estimatedState);
				    currSC.propSigma = GenerateSigmaPoints(currSC.xhatPrev, Ptemp, numStates, numSigmas);

				    RealVector rawMeas = updatePrep(currSC, tm, currSC.associatedObsIndex.get(measIndex), numSigmas, propagator, biasPos);
				    RealMatrix Pyy = R.copy();
				    RealVector yhatpre = addColumns(currSC.estimMeas).mapMultiplyToSelf(weight);
				    for (int i = 0; i < numSigmas; i++)
				    {
					RealVector y = currSC.estimMeas.getColumnVector(i).subtract(yhatpre);
					Pyy = Pyy.add(y.outerProduct(y).scalarMultiply(weight));
				    }
				    currSC.estOutput.get(measIndex).innovationCovariance = Pyy.getData();
				    
				    //Add each measurement to the list of associated measurements.
			    	removeObs.add(currSC.associatedObsIndex.get(measIndex));
			    				    				    	
			    	currSC.associatedObsIndex.set(measIndex,odObs.measObjs.indexOf(rawMeasurements.get(currSC.associatedObsIndex.get(measIndex)).measObj));
			    	
				}
				
				promotedTracks.add(currSC);
				residentSpaceObjects.remove(objNum);
				
				totalObjNum--;
		    }
		    else
		    {
		    	objNum++;
		    }
		}   

		if (promotedTrackReset)
		{	
			//remove associated obs from raw measurement dataset
			Collections.sort(removeObs);

			for(int measNum = removeObs.size()-1; measNum >= 0; measNum--)
			{
				rawMeasurements.remove((int)removeObs.get(measNum));
			}
		    
			if(rawMeasurements.size() == 0)
		    break;
			
			if(residentSpaceObjects.size() == 0)
			{
				if(enableCAR == true)
				{
					activateCAR = true;
					smoothIter = -1;

				}
				else
					break;
			}
		}
		
	    }

	    //Save output

	    multiOutput.estOutput = new ArrayList<ArrayList<Estimation.EstimationOutput>>(promotedTracks.size());
	    multiOutput.associatedObs = new ArrayList<ArrayList<Integer>>(promotedTracks.size());
	    for (SingleObject obj: promotedTracks)
	    {
		multiOutput.estOutput.add(obj.estOutput);
		multiOutput.associatedObs.add(obj.associatedObsIndex);		
	    }
	    
	    multiOutput.unassociatedObs = new ArrayList<Integer>();
	    
	    for (measurementObject o: rawMeasurements)
	    {
		int idx = odObs.measObjs.indexOf(o.measObj);
		if (idx != -1)
		    multiOutput.unassociatedObs.add(idx);
	    }
	}

	void MergeHypotheses(ArrayList<SingleObject> hypotheses)
	{
		int hypNum1 = 0;
		
		while(hypNum1 < hypotheses.size())
		{	
			int hypNum2 = hypNum1+1;

			while(hypNum2 < hypotheses.size())
			{	
								
				SingleObject obj1 = hypotheses.get(hypNum1);
				SingleObject obj2 = hypotheses.get(hypNum2);
				//Combine distributions
				RealMatrix MahalaTemp = MatrixUtils.createColumnRealMatrix(obj1.xhat.subtract(obj2.xhat).toArray());

				RealMatrix Mahalanobis1 = MahalaTemp.transpose().multiply(MatrixUtils.inverse(obj1.P).multiply(MahalaTemp));
				RealMatrix Mahalanobis2 = MahalaTemp.transpose().multiply(MatrixUtils.inverse(obj2.P).multiply(MahalaTemp));
								
				//If mahalanobis distance low, combine distributions.
				if(Math.min(Mahalanobis1.getEntry(0,0),Mahalanobis2.getEntry(0,0)) < 1)
				{
					RealVector xhatTemp =  obj1.xhat.mapMultiply(obj1.hypothesisWeight).add(obj2.xhat.mapMultiply(obj2.hypothesisWeight));
					
					RealMatrix innovTemp = MatrixUtils.createColumnRealMatrix(obj1.xhat.subtract(xhatTemp).toArray());
					
					RealMatrix PTemp = (obj1.P.add(innovTemp.multiply(innovTemp.transpose()))).scalarMultiply(obj1.hypothesisWeight);
					
					innovTemp = MatrixUtils.createColumnRealMatrix(obj2.xhat.subtract(xhatTemp).toArray());

					PTemp = PTemp.add((obj2.P.add(innovTemp.multiply(innovTemp.transpose()))).scalarMultiply(obj2.hypothesisWeight));

					hypotheses.get(hypNum1).xhat = new ArrayRealVector(xhatTemp);
					hypotheses.get(hypNum1).P = PTemp;
					hypotheses.get(hypNum1).hypothesisWeight += hypotheses.get(hypNum2).hypothesisWeight;
					
					hypotheses.remove(hypNum2);

				}
				else
				{
					hypNum2++;
				}
			}
			
			hypNum1++;
		}
	}
	
	void PruneHypotheses(ArrayList<SingleObject> hypotheses)
	{
		double maxWeight = 0;
		for(int hypNum = 0; hypNum < hypotheses.size(); hypNum++)
		{
			if(hypotheses.get(hypNum).hypothesisWeight > maxWeight)
				maxWeight = hypotheses.get(hypNum).hypothesisWeight;
		
		}
		
		int hypNum = 0;
				
		while(hypNum < hypotheses.size())
		{	

			if(hypotheses.get(hypNum).hypothesisWeight < maxWeight * 1E-3)
			{
				hypotheses.remove(hypNum);
			}
			else
			{
				hypNum++;
			}
		}
	}
	
	ArrayList<Hypothesis> GenerateHypotheses(Measurements.Measurement obs, double sigmaRA, double sigmaRAd, double sigmaDec, double sigmaDecd, int numStates, int numSigmas)
	{
		AbsoluteDate date = new AbsoluteDate(obs.time, DataManager.getTimeScale("UTC"));
				
		double RA = obs.rightAscension;
		double RA_d = obs.angleRates[0];
		
		double Dec = obs.declination;
		double Dec_d = obs.angleRates[1];
		
		TimeStampedPVCoordinates stationCoords = odCfg.stations.get(obs.station).getBaseFrame().getPVCoordinates(date, odCfg.propFrame);
		
		ArrayList <CAR.CARGaussianElement> CARGaussians = new CAR(RA, RA_d, Dec, Dec_d, stationCoords, 1000.0, 1.0, 1000.0 ,2.656e7, 2.657e7, 0.001).getCAR();

		ArrayList<Hypothesis> objectHypotheses= new ArrayList<Hypothesis>();
		
		
		for(int i = 0; i < CARGaussians.size(); i++)
		{
			Hypothesis singleHypothesis= new Hypothesis();
			
			RealVector meanTemp = new ArrayRealVector(new double[] {CARGaussians.get(i).rangeMean, RA, Dec, CARGaussians.get(i).rangeRateMean, RA_d, Dec_d, odCfg.getInitialState()[6], odCfg.getInitialState()[7]});
			RealMatrix CovarTemp = new DiagonalMatrix(new double[] {CARGaussians.get(i).rangeStd, sigmaRA, sigmaDec, CARGaussians.get(i).rangeRateStd, sigmaRAd, sigmaDecd, odCfg.estmCovariance[6], odCfg.estmCovariance[7]});
			
			Array2DRowRealMatrix sigma = GenerateSigmaPoints(meanTemp, CovarTemp, numStates, numSigmas);
			Array2DRowRealMatrix sigmaXYZ = new Array2DRowRealMatrix(numStates, numSigmas);

			for (int j = 0; j < sigma.getColumnDimension(); j++)
			{
				sigma.setColumn(j, RangeRaDec2XYZ(sigma.getColumnVector(j), date, obs.station));
			}
			
		    new ManualPropagation(odCfg).propagate(0, sigma, CARGaussians.get(i).rangeMean/Constants.SPEED_OF_LIGHT, sigmaXYZ, false);

			RealMatrix Pxx = new Array2DRowRealMatrix(numStates, numStates);
			RealVector xhat = addColumns(sigmaXYZ).mapMultiplyToSelf(0.5/numStates);
			
			for (int j = 0; j < sigmaXYZ.getColumnDimension(); j++)
			{
			    RealVector xhatdiff = sigmaXYZ.getColumnVector(j).subtract(xhat);
			    Pxx = Pxx.add(xhatdiff.outerProduct(xhatdiff).scalarMultiply(0.5/numStates));
			}
			
			
			//return mean/covar structure
			singleHypothesis.xhat = new ArrayRealVector(xhat.toArray());
			singleHypothesis.P = Pxx;
			singleHypothesis.weight = CARGaussians.get(i).weight;

			
			objectHypotheses.add(singleHypothesis);
			
			
			
		}
		
		return objectHypotheses;
	}
	
	private	double[] RangeRaDec2XYZ(RealVector RangeRaDec, AbsoluteDate date, String stat)
	{
		
		//CAR FrameTransfer Test
		
		double Range = RangeRaDec.getEntry(0);
		double Range_d = RangeRaDec.getEntry(3);		
		double RA = RangeRaDec.getEntry(1);
		double RA_d = RangeRaDec.getEntry(4);		
		double Dec = RangeRaDec.getEntry(2);
		double Dec_d = RangeRaDec.getEntry(5);
		
		
		//Compute Inertial pos/vel relative to station
		Vector3D topoPos = new Vector3D(new double[] {Range*Math.cos(Dec)*Math.cos(RA), Range*Math.cos(Dec)*Math.sin(RA), Range*Math.sin(Dec)});
		Vector3D topoVel = new Vector3D(new double[] {Range_d*Math.cos(Dec)*Math.cos(RA) - Range*Math.sin(Dec)*Math.cos(RA)*Dec_d - Range*Math.cos(Dec)*Math.sin(RA)*RA_d,
															   Range_d*Math.cos(Dec)*Math.sin(RA) - Range*Math.sin(Dec)*Math.sin(RA)*Dec_d + Range*Math.cos(Dec)*Math.cos(RA)*RA_d,
															   Range_d*Math.sin(Dec) + Range*Math.cos(Dec) * Dec_d});
		

		//get station coords
		//add station coords to observation coords.
		Vector3D Pos = new Vector3D(1, topoPos, 1, odCfg.stations.get(stat).getBaseFrame().getPVCoordinates(date, odCfg.propFrame).getPosition());
		Vector3D Vel = new Vector3D(1, topoVel, 1, odCfg.stations.get(stat).getBaseFrame().getPVCoordinates(date, odCfg.propFrame).getVelocity());

		
		
		return new double[] {Pos.getX(), Pos.getY(), Pos.getZ(), Vel.getX(), Vel.getY(), Vel.getZ(), RangeRaDec.getEntry(6), RangeRaDec.getEntry(7)};
				
		
	}
	
	private ArrayRealVector addColumns(RealMatrix mat)
	{
	    double[][] arr = mat.getData();
	    int m = mat.getRowDimension();
	    int n = mat.getColumnDimension();
	    ArrayRealVector out = new ArrayRealVector(m);

	    for (int j = 0; j < m; j++)
	    {
		double sum = 0.0;
		for (int i = 0; i < n; i++)
		    sum += arr[j][i];
		out.setEntry(j, sum);
	    }

	    return(out);
	}
	
	private RealVector quadCheck(RealVector measurement, RealVector state)
	{
	    //Innovations QuadCheck
		RealVector Innov = measurement.subtract(state);
	
		if(combinedMeas && Innov.getEntry(0) > Math.PI)
		{
			Innov.setEntry(0, Innov.getEntry(0) - 2 * Math.PI);
		}
		else if(combinedMeas && Innov.getEntry(0) < -1*  Math.PI)
		{
			Innov.setEntry(0, Innov.getEntry(0) + 2 * Math.PI);
		}
		
		return Innov;
	}

	private ArrayList<ArrayList<JPDALikelihoods>> JPDAJointEvents(ArrayList<ArrayList<JPDALikelihoods>> JointEvents,
							      double[][] MarginalEvents, ArrayList<JPDALikelihoods> SingleJointEvent, int objNum)
	{
		for(int measNum = 0; measNum<MarginalEvents[objNum].length; measNum++)
		{	
			ArrayList<JPDALikelihoods> SingleJointEventTemp = new ArrayList<JPDALikelihoods>();
			//Copy Single Joint Event into Single Joint Event Temp
			for(int m = 0; m < SingleJointEvent.size(); m++)
			    SingleJointEventTemp.add(SingleJointEvent.get(m));
			//Add event if that measurement has not been used
			boolean skip = false;
			for(int m = 0; m < SingleJointEvent.size(); m++)
			{
			    if(measNum == SingleJointEventTemp.get(m).measurement && measNum != 0)
				skip = true;
			}
	
			if (skip)
			    continue;
			JPDALikelihoods temp = new JPDALikelihoods();
			
			temp.measurement = measNum;
			temp.object = objNum;
			temp.psi = MarginalEvents[objNum][measNum];
			
			SingleJointEventTemp.add(temp);
			//decide to repeat or not
			if(MarginalEvents.length == objNum+1)
			    JointEvents.add(SingleJointEventTemp);
			else
			    JointEvents = JPDAJointEvents(JointEvents, MarginalEvents, SingleJointEventTemp, objNum+1);
		}
		return JointEvents;
	}
	
	private Array2DRowRealMatrix GenerateSigmaPoints(RealVector mean, RealMatrix Cov, int numStates, int numSigmas)
	{
		Array2DRowRealMatrix sigma = new Array2DRowRealMatrix(numStates, numSigmas);
		
		RealMatrix Ptemp = Cov.scalarMultiply(numStates);
		
		RealMatrix sqrP = new CholeskyDecomposition(
				Ptemp.add(Ptemp.transpose()).scalarMultiply(0.5), 1E-6, 1E-16).getL();

		for (int j = 0; j < numStates; j++)
		{
			sigma.setColumnVector(j, mean.add(sqrP.getColumnVector(j)));
			sigma.setColumnVector(numStates + j, mean.subtract(sqrP.getColumnVector(j)));
		}
		
		
		return sigma;
	}
	
	private RealVector updatePrep(SingleObject currSC, AbsoluteDate tm, int measIndex, int numSigmas,
				      ManualPropagation propagator, HashMap<String, Integer> biasPos)
	{		
		//Transforms measurement Sig Points and stores in currSC. Also returns rawMeas
		RealVector rawMeas = null; // same as raw in my code
		for (int i = 0; i < numSigmas; i++)
		{
		    double[] pv = currSC.propSigma.getColumn(i);
		    final SpacecraftState[] ssta = new SpacecraftState[1];

		    ssta[0] = new SpacecraftState(new CartesianOrbit(new PVCoordinates(new Vector3D(pv[0], pv[1], pv[2]),
										       new Vector3D(pv[3], pv[4], pv[5])),
								     odCfg.propFrame, tm, Constants.EGM96_EARTH_MU),
						  propagator.getAttitude(tm, pv), odCfg.rsoMass);

		    if (combinedMeas || measNames.length == 1)
		    {
			double[] fitv = rawMeasurements.get(measIndex).measObj.estimate(0, 0, ssta).getEstimatedValue();
			currSC.estimMeas.setColumn(i, fitv);
			if (rawMeas == null)
			    rawMeas = new ArrayRealVector(rawMeasurements.get(measIndex).measObj.getObservedValue());
		    }
		    else
		    {
			double[] fitv = rawMeasurements.get(2*measIndex).measObj.estimate(0, 0, ssta).getEstimatedValue();
			currSC.estimMeas.setEntry(0, i, fitv[0]);
			fitv = rawMeasurements.get(2*measIndex+1).measObj.estimate(0, 0, ssta).getEstimatedValue();
			currSC.estimMeas.setEntry(1, i, fitv[0]);
			if (rawMeas == null)
			    rawMeas = new ArrayRealVector(new double[]{rawMeasurements.get(2*measIndex).measObj.getObservedValue()[0],
			    			rawMeasurements.get(2*measIndex+1).measObj.getObservedValue()[0]});
		    }
		}

		//Does not enter if meas is pos/vel. Otherwise will enter.
		if (rawMeasurements.get(measIndex).rawmeas.station != null)
		{
		    String name = new StringBuilder(rawMeasurements.get(measIndex).rawmeas.station).append(measNames[0]).toString();
		    Integer pos = biasPos.get(name);
		    if (pos != null)
		    {
			if (measNames.length == 2)
			{
			    name = new StringBuilder(rawMeasurements.get(measIndex).rawmeas.station).append(measNames[1]).toString();
			    rawMeas = rawMeas.subtract(new ArrayRealVector(new double[]{currSC.xhatPrev.getEntry(pos),
			    						currSC.xhatPrev.getEntry(pos)}));
			}
			else
			    rawMeas = rawMeas.subtract(new ArrayRealVector(new double[]{currSC.xhatPrev.getEntry(pos)}));
		    }
		}

		return rawMeas;
	}

	class smootherTimeStep
	{
	    RealMatrix Ppre;
	    RealMatrix Ppost;
	    RealMatrix xpre;
	    RealMatrix xpost;
	    RealMatrix sigPre;
	    RealMatrix sigPost;

	    RealMatrix xstar;
	    RealMatrix Pstar;

	    AbsoluteDate tmSmoother;
	    ObservedMeasurement measObjsSmoother;
	    ObservedMeasurement measObjsSmootherNoComb;    		
	}

	class JPDALikelihoods
	{
	    double psi;
	    int object;
	    int measurement;
	}

	class measurementObject
	{
		ObservedMeasurement measObj;
		Measurements.Measurement rawmeas;
	}
	
	class SingleObject
	{
	    double[] xInitial;
	    RealMatrix PInitial;
	    RealMatrix P;
	    final RealMatrix psdCorr;
	    final Array2DRowRealMatrix sigma;
	    Array2DRowRealMatrix propSigma;
	    final Array2DRowRealMatrix estimMeas;

	    RealMatrix Pprop;
	    ArrayRealVector xhat;
	    RealVector xhatPrev;
	    double hypothesisWeight;
	    
	    ArrayList<smootherTimeStep> smootherData;
	    ArrayList<JPDALikelihoods> marginalEvents;
	    ArrayList<Integer> associatedObsIndex;
	    ArrayList<Estimation.EstimationOutput> estOutput;

	    boolean dataAssociated;
	    boolean McReynoldsConsistencyPass;

	    public SingleObject(Settings Config, int numStates, int numSigmas, int Rsize)
	    {
		xInitial = Config.getInitialState();
		PInitial = new DiagonalMatrix(Config.estmCovariance);
		P = PInitial;
		psdCorr = MatrixUtils.createRealIdentityMatrix(P.getRowDimension()).scalarMultiply(1.0E-6);
		sigma = new Array2DRowRealMatrix(numStates, numSigmas);
		propSigma = new Array2DRowRealMatrix(numStates, numSigmas);
		estimMeas = new Array2DRowRealMatrix(Rsize, numSigmas);
		Pprop = null;
		xhat = new ArrayRealVector(xInitial);
		xhatPrev = new ArrayRealVector(xInitial);
		smootherData = new ArrayList<smootherTimeStep>();
		marginalEvents = new ArrayList<JPDALikelihoods>();
		associatedObsIndex = new ArrayList<Integer>();
		estOutput = new ArrayList<Estimation.EstimationOutput>();
		dataAssociated = true;
		hypothesisWeight = 1;
	    }
	    
	    public SingleObject(double[] x, RealMatrix Covar, int numStates, int numSigmas, int Rsize, double hypWeight)
	    {
		xInitial = x;
		PInitial = Covar;
		P = PInitial;
		psdCorr = MatrixUtils.createRealIdentityMatrix(P.getRowDimension()).scalarMultiply(1.0E-6);
		sigma = new Array2DRowRealMatrix(numStates, numSigmas);
		propSigma = new Array2DRowRealMatrix(numStates, numSigmas);
		estimMeas = new Array2DRowRealMatrix(Rsize, numSigmas);
		Pprop = null;
		xhat = new ArrayRealVector(xInitial);
		xhatPrev = new ArrayRealVector(xInitial);
		smootherData = new ArrayList<smootherTimeStep>();
		marginalEvents = new ArrayList<JPDALikelihoods>();
		associatedObsIndex = new ArrayList<Integer>();
		estOutput = new ArrayList<Estimation.EstimationOutput>();
		dataAssociated = true;
		hypothesisWeight = hypWeight;

	    }
	}
	
    class Hypothesis
    {
    	
        ArrayRealVector xhat;
 	    RealMatrix P;    
 	    double weight;
 	    
    }

	
    }
    
}
