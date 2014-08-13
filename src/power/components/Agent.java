package power.components;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.TreeSet;

import com.sun.java.swing.plaf.windows.resources.windows;

import cern.colt.Arrays;

import power.SmartGridBuilder;
import power.components.generators.AgentGenerator;
import power.helpers.AdjustedRunningMax;
import power.helpers.Computations;
import power.helpers.HelperFunctions;
import power.helpers.PeriodicFullMinMax;
import power.helpers.RunningMean;
import power.models.IRandomModel;
import power.tools.Absolute;
import power.tools.Adjuster;
import power.tools.Clamp;
import power.tools.IAdjuster;
import power.tools.IDescribable;
import power.tools.IListAccessor;
import power.tools.ISimpleAdjuster;

import repast.simphony.engine.schedule.ScheduledMethod;
import repast.simphony.essentials.RepastEssentials;
import repast.simphony.random.RandomHelper;
import repast.simphony.space.graph.RepastEdge;

/**
 * @author That
 * 
 */
public class Agent implements IDescribable {

	// enumerations
	private enum SimulationPhase {
		Init, Exec, Fin
	}

	private enum Entity {
		Grid, Neighbors, Generation, Storage
	}

	private SimulationPhase phase;
	private int period;

	// Fundamental characteristics
	private final Grid grid;
	private final AgentGenerator group;
	private final IRandomModel suspendableModel;
	private final IRandomModel loadModel;
	private final IRandomModel generationModel;
	private final IReliability reliability;
	private final Storage storage;
	private final int foresight;
	private final boolean smart;

	// Agent memory
	private final AdjustedRunningMax demandWindow;
	private final RunningMean deficitWindow;
	private final PeriodicFullMinMax neighborhoodRequestWindow;
	// private final RunningMean priceWindow;

	// Characteristics
	private boolean isConnectedToGrid;
	private double currentlySatisfiedLoad;
	private double currentlySpentGeneration;

	private double suspendedLoad;
	private double currentAddedSuspension;
	private double currentRemovedSuspension;

	// zero by default
	private double currentPowerBoughtFromGrid;
	private double currentPowerBoughtFromNeighbors;
	private double currentPowerSoldToGrid;
	private double currentPowerSoldToNeighbors;
	private double currentNeighborhoodRequest;
	private double currentDumpedPower;
	private double currentForcefullySuspendedLoad;

	// accounting
	// zero by default
	private double currentDebitToGrid;
	private double currentDebitToNeighbors;
	private double currentCreditFromGrid;
	private double currentCreditFromNeighbors;

	// zero by default
	private double currentStorageCost;
	private double currentGenerationCost;

	private static int population = 0;
	private int id;

	public Agent(Grid grid,
			AgentGenerator group,
			IReliability reliability,
			IRandomModel suspendableModel,
			IRandomModel loadModel,
			IRandomModel generatorModel,
			Storage storage,
			int foresight,
			boolean smart) {
		id = population++;
		isConnectedToGrid = true;
		// System.out.println("Constructing: " + this.toString());

		this.grid = grid;
		this.group = group;
		this.smart = smart;

		if (reliability == null) {
			this.reliability = BlankReliability.getBlankReliability();
		} else {
			this.reliability = reliability;
		}

		this.suspendableModel = suspendableModel;
		this.suspendableModel.getModel().setSource(this);

		this.loadModel = loadModel;
		this.loadModel.getModel().setSource(this);

		this.generationModel = generatorModel;
		if (this.generationModel.getModel() != null) {
			this.generationModel.getModel().setSource(this);
		}

		this.storage = storage;
		this.foresight = foresight;

		demandWindow = new AdjustedRunningMax(Absolute.getSingleton());
		deficitWindow = new RunningMean();
		neighborhoodRequestWindow = new PeriodicFullMinMax();
		// priceWindow = grid.getPriceWindow();
	}

	@ScheduledMethod(start = 0, interval = 1, priority = 1)
	public void initialize() {
		phase = SimulationPhase.Init;
		period = ((int) RepastEssentials.GetTickCount()) % SmartGridBuilder.getPeriod();

		hasBeenOrdered = false;
		
		// clean memoirs
		underAverageDeficit = null;
		underAverageRequest = null;
//		underSeaRequest = null;
		overAverageDeficit = null;
		overAverageRequest = null;
		
		localUnderAverageDeficit = null;
		localUnderAverageRequest = null;
//		localUnderSeaRequest = null;
		localOverAverageDeficit = null;
		localOverAverageRequest = null;
//		localOverSeaRequest = null;
//		localOverMinRequest = null;
		
		dynamicOverAverageDeficit = null;
		dynamicUnderAverageDeficit = null;

//		requestSeaLevel = null;
		
		buyingFromNeighborsAvailability = null;
		neighborhoodRequestRation = null;

		// load
		currentlySatisfiedLoad = 0;
		currentlySpentGeneration = 0;
		currentAddedSuspension = 0;
		currentRemovedSuspension = 0;
		currentForcefullySuspendedLoad = 0;

		currentPowerBoughtFromGrid = 0;
		currentPowerBoughtFromNeighbors = 0;
		currentPowerSoldToGrid = 0;
		currentPowerSoldToNeighbors = 0;
		currentNeighborhoodRequest = 0;
		currentDumpedPower = 0;

		currentStorageCost = storage.getCostFactor() * storage.getCapacity();
		currentGenerationCost = generationModel.getCostFactor() * generationModel.getCurrentValue();

		currentDebitToGrid = 0;
		currentDebitToNeighbors = 0;
		currentCreditFromGrid = 0;
		currentCreditFromNeighbors = 0;

		deficitWindow.add(getDeficit());
	}

	@ScheduledMethod(start = 0, interval = 1, priority = -1)
	public void finalize() {
		phase = SimulationPhase.Fin;

		suspendedLoad = getCurrentSuspendedLoad();
		demandWindow.add(getCurrentDemand());
		neighborhoodRequestWindow.add(getCurrentNeighborhoodRequest());
	}

	@ScheduledMethod(start = 0, interval = 1, priority = 0)
	public void execute() {
//		System.out.println("+" + RepastEssentials.GetTickCount() + ": " + toString());
		phase = SimulationPhase.Exec;

		spendGeneratedPower(satisfyLoad(getCurrentRemainingGeneration()));
		double meanDeficit = deficitWindow.getWindowMean();
		double power = meanDeficit - getDeficit();

		if (meanDeficit > 0) {
			if (getDeficit() == meanDeficit) {
				// 0 < mean == def
				buyFromGrid(satisfyLoad(getBuyingFromGridAvailability(meanDeficit))); // ok
			} else if (getDeficit() > meanDeficit) {
				// 0 < mean < def
				buyFromGrid(satisfyLoad(getBuyingFromGridAvailability(meanDeficit))); // ok
				
				double battRation = Clamp.getUnit().adjust(getCurrentStoredPower() / getLocalOverAverageDeficit()) * getCurrentUnsatisfiedLoad(); // ok
				spendStoredPower(satisfyLoad(getStoragePowerAvailability(battRation))); // ok
				
				//suspendLoad(getCurrentUnsatisfiedLoad()); // ok
			} else if (getDeficit() > 0) {
				// 0 < def < mean
				satisfyLoad(buyFromGrid(getCurrentUnsatisfiedLoad())); // ok
				
				double susRation = Clamp.getUnit().adjust(getCurrentSuspendedLoad() / getLocalUnderAverageDeficit()) * power;
				double battShare = Clamp.getDefault().adjust(getLocalUnderAverageDeficit() - getCurrentSuspendedLoad());
				double susPower = buyFromGrid(satisfySuspendedLoad(getBuyingFromGridAvailability(susRation)));
				double battRation = (battShare > 0 ? Clamp.getUnit().adjust(storage.getCurrentAvailableCapacity() / battShare) : 0) * (power - susPower);
				buyFromGrid(storage.energize(getBuyingFromGridAvailability(battRation)));
				
				// 0 < def < mean
//				satisfyLoad(buyFromGrid(getCurrentUnsatisfiedLoad())); // ok
//				
//				double susRation = Clamp.getUnit().adjust(getCurrentSuspendedLoad() / getUnderAverageDeficit()) * power;
//				double battRation = power - buyFromGrid(satisfySuspendedLoad(getBuyingFromGridAvailability(susRation)));
//
//				buyFromGrid(storage.energize(getBuyingFromGridAvailability(battRation)));
			} else {
				// def <= 0 < mean
				spendGeneratedPower(satisfySuspendedLoad(getCurrentRemainingGeneration()));
				
				double susRation = Clamp.getUnit().adjust(getCurrentSuspendedLoad() / getLocalUnderAverageDeficit()) * power;
				double battShare = Clamp.getDefault().adjust(getLocalUnderAverageDeficit() - getCurrentSuspendedLoad());
				double susPower = buyFromGrid(satisfySuspendedLoad(getBuyingFromGridAvailability(susRation)));
				double battRation = (battShare > 0 ? Clamp.getUnit().adjust(storage.getCurrentAvailableCapacity() / battShare) : 0) * (power - susPower);
				buyFromGrid(storage.energize(getBuyingFromGridAvailability(battRation)));
				
//				spendGeneratedPower(storage.energize(getCurrentRemainingGeneration()));
				
				if(SmartGridBuilder.getForceDumping()) {
					spendGeneratedPower(dumpPower(getCurrentRemainingGeneration()));
				}
			}
		} else {
			if (getDeficit() == meanDeficit) {
				// def = mean <= 0
				spendGeneratedPower(sellToGrid(getGenerationAvailability(-meanDeficit)));
			} else if (getDeficit() < meanDeficit) {
				// def < mean <= 0
				spendGeneratedPower(sellToGrid(getGenerationAvailability(-meanDeficit)));
				
				double susRation = Clamp.getUnit().adjust(getCurrentSuspendedLoad() / getLocalUnderAverageDeficit()) * power;
				double battShare = Clamp.getDefault().adjust(getLocalUnderAverageDeficit() - getCurrentSuspendedLoad());
				double susPower = spendGeneratedPower(satisfySuspendedLoad(getGenerationAvailability(susRation)));
				double battRation = (battShare > 0 ? Clamp.getUnit().adjust(storage.getCurrentAvailableCapacity() / battShare) : 0) * (power - susPower);
				spendGeneratedPower(storage.energize(getGenerationAvailability(battRation)));
				
//				spendGeneratedPower(storage.energize(getCurrentRemainingGeneration()));

				if (SmartGridBuilder.getForceDumping()) {
					spendGeneratedPower(dumpPower(getCurrentRemainingGeneration()));
				}
			} else if (getDeficit() < 0) {
				// mean < def < 0
				spendGeneratedPower(sellToGrid(getCurrentRemainingGeneration()));

				double battRation = -Clamp.getUnit().adjust(getCurrentStoredPower() / getLocalOverAverageDeficit()) * power;
				spendStoredPower(sellToGrid(getStoragePowerAvailability(battRation)));
			} else {
				// mean <= 0 <= def and mean != def				
				double battRation = -Clamp.getUnit().adjust(getCurrentStoredPower() / getLocalOverAverageDeficit()) * power;
				battRation -= spendStoredPower(satisfyLoad(getStoragePowerAvailability(battRation)));
				spendStoredPower(sellToGrid(getStoragePowerAvailability(battRation)));
			}
		}

		if (SmartGridBuilder.getCanTrade() &&
				storage.getCurrentAvailableCapacity() > getLocalUnderAverageDeficit() &&
//				getRequestSeaLevel() > getPredictedNeighborhoodRequest()) {
				getMeanNeighborhoodRequest() > getPredictedNeighborhoodRequest()) {
			
			double request = getMeanNeighborhoodRequest() - getPredictedNeighborhoodRequest();
//			double requestRation = Clamp.getUnit().adjust(request / getUnderAverageRequest()) * neighborhoodRequestWindow.getRecentSum();
			double requestRation = Clamp.getUnit().adjust((storage.getCurrentAvailableCapacity() - getLocalUnderAverageDeficit()) / getLocalUnderAverageRequest()) * request;
//			double requestRation = Clamp.getUnit().adjust((storage.getCurrentAvailableCapacity() - getLocalUnderAverageDeficit()) / getLocalUnderSeaRequest()) * underExpAdjuster.adjust(getRequestSeaLevel() - getPredictedNeighborhoodRequest()) * constant();

			double value = spendGeneratedPower(storage.energize(getGenerationAvailability(requestRation)));
			requestRation -= value;
//			if (grid.getCurrentBasePrice() < grid.getMeanBasePrice()) {
				value += buyFromGrid(storage.energize(getBuyingFromGridAvailability(requestRation)));
//			}
		}
		
		// fix remaining load
//		if (grid.getCurrentBasePrice() > grid.getMeanBasePrice()) {
			satisfyLoad(buyFromNeighbors(getCurrentUnsatisfiedLoad())); //ok
//		}
		suspendLoad(getCurrentUnsatisfiedLoad());
		satisfyLoad(buyFromGrid(getCurrentUnsatisfiedLoad())); // ok
		satisfyLoad(spendStoredPower(getCurrentUnsatisfiedLoad())); // ok
		
		// fix remaining generation
		spendGeneratedPower(satisfySuspendedLoad(getCurrentRemainingGeneration()));
		if (SmartGridBuilder.getBeGenerationScrooge()) {
			spendGeneratedPower(storage.energize(getCurrentRemainingGeneration()));
		}
		spendGeneratedPower(sellToGrid(getCurrentRemainingGeneration()));
		spendGeneratedPower(dumpPower(getCurrentRemainingGeneration())); // ok
		
		forcefullySuspendLoad(getCurrentUnsatisfiedLoad()); // ok
//		System.out.println("-" + RepastEssentials.GetTickCount() + ": " + toString());
	}

	/*
	 * Blackout methods
	 */
	public void reconnectToGrid() {
		isConnectedToGrid = true;
	}

	public void disconnectFromGrid() {
		isConnectedToGrid = false;
	}

	public boolean isConnectedToGrid() {
		return isConnectedToGrid;
	}

	/*
	 * interaction methods
	 */
	private double getBuyingFromGridAvailability(double power) {
		if (!isConnectedToGrid)
			return 0.0;
		return power;
	}

	private double buyFromGrid(double power) {
		if (!isConnectedToGrid)
			return 0.0;
		double powerBought = grid.buyFromGrid(power);
		currentPowerBoughtFromGrid += powerBought;
		currentDebitToGrid += grid.getSellingValue(powerBought);
		return powerBought;
	}

	private double getSellingToGridAvailability(double power) {
		if (!isConnectedToGrid)
			return 0.0;
		return grid.getSellToGridAvailability(power);
	}

	private double sellToGrid(double power) {
		if (!isConnectedToGrid)
			return 0.0;

		double soldPower = grid.sellToGrid(power);
		currentPowerSoldToGrid += soldPower;
		currentCreditFromGrid += grid.getBuyingValue(soldPower);
		return soldPower;
	}

	private Double buyingFromNeighborsAvailability;

	private double getBuyingFromNeighborsAvailability() {
		if (buyingFromNeighborsAvailability == null) {
			double available = 0;
			for (Object obj : SmartGridBuilder.getNetwork().getAdjacent(this)) {
				if (obj instanceof Agent) {
					Agent agent = (Agent) obj;
					available = agent.getSellingToNeighborsAvailability(this);
				}
			}
			buyingFromNeighborsAvailability = available;
		}
		
		if (buyingFromNeighborsAvailability > 0) {
			System.out.println("Say: " + buyingFromNeighborsAvailability);
		}
		return buyingFromNeighborsAvailability;
	}

	private double getBuyingFromNeighborsAvailability(double request) {
		if (request > getBuyingFromNeighborsAvailability())
			return getBuyingFromNeighborsAvailability();
		return request;
	}

	private double buyFromNeighbors(double request) {
		if (!SmartGridBuilder.getCanTrade() || request <= 0) return 0;
		ArrayList<Neighbor> neighbors = getNeighborhood();

		double available = 0;
		for (int index = neighbors.size() - 1; index >= 0; index--) {
			if (request <= 0)
				break;

			double power = neighbors.get(index).getAgent().requestToBuy(this, request);
			neighbors.get(index).setValue(Computations.getExponentialAverage(neighbors.get(index).getValue(), (power / request)));
			request -= power;
			available += power;
		}

		if (buyingFromNeighborsAvailability != null)
			buyingFromNeighborsAvailability -= available;
		currentPowerBoughtFromNeighbors += available;
		currentDebitToNeighbors = grid.getBaseValue(currentPowerBoughtFromNeighbors);
		return available;
	}

	private double getSellingToNeighborsAvailability(Agent requester) {
		if (!reliability.isOperational() ||
				!SmartGridBuilder.getCanTrade() || 
//				grid.getCurrentBasePrice() <= grid.getMeanBasePrice() ||
//				getPredictedNeighborhoodRequest() <= neighborhoodRequestWindow.getPeriodMin() ||
				getCurrentStoredPower() <= getLocalOverAverageDeficit() ||
				getMeanNeighborhoodRequest() >= getPredictedNeighborhoodRequest() ||
				currentNeighborhoodRequest >= getNeighborhoodRequestRation())
		return 0.0;
		
		return getStoragePowerAvailability(getNeighborhoodRequestRation() - currentPowerSoldToNeighbors);
//		return getStoragePowerAvailability(getCurrentStoredPower() - getLocalOverAverageDeficit());
	}

	private double getSellingToNeighborsAvailability(Agent requester, double power) {
		double available = getSellingToNeighborsAvailability(requester);
		if (power > available) {
			return available;
		} else {
			return power;
		}
	}

	private Double neighborhoodRequestRation = null;

	private double getNeighborhoodRequestRation() {
		if (neighborhoodRequestRation == null) {
//			neighborhoodRequestRation = Clamp.getUnit().adjust((getCurrentStoredPower() - getLocalOverAverageDeficit())/getLocalOverSeaRequest()) * (getPredictedNeighborhoodRequest() - neighborhoodRequestWindow.getPeriodMin());
			neighborhoodRequestRation = Clamp.getUnit().adjust((getCurrentStoredPower() - getLocalOverAverageDeficit()) / getLocalOverAverageRequest()) * getPredictedNeighborhoodRequest();
		}
		return neighborhoodRequestRation;
	}

	/**
	 * The agent check if it has generated a surplus of power, if so the agent
	 * returns some as much requested power as possible from the surplus Note:
	 * This agent and the requester should be neighbors
	 * 
	 * @param request
	 *            The amount of power being requested to be bought
	 * @return the amount of power bought
	 */
	private double requestToBuy(Agent requester, double request) {
//		System.out.println("+requestToBuy(" + requester.toString() + ", " + request + ")");
		double power = storage.draw(getSellingToNeighborsAvailability(requester, request));
		
		currentNeighborhoodRequest += request;
		currentPowerSoldToNeighbors += power;
		currentCreditFromNeighbors = grid.getBaseValue(currentPowerSoldToNeighbors);
		if (power > 0) {
			RepastEdge<Object> edge = SmartGridBuilder.getNetwork().getEdge(requester, this);
			if (edge != null) {
				SmartGridBuilder.getNetwork().removeEdge(edge);
				SmartGridBuilder.getNetwork().addEdge(this, requester);
			}
			edge = SmartGridBuilder.getNetwork().getEdge(this, requester);
			edge.setWeight(power);
		}
//		System.out.println("-requestToBuy(): " + power);
		return power;
	}

	// End of interaction method segment

	/*
	 * Start of computations section
	 */
	private Double underAverageDeficit = null;
	private double getUnderAverageDeficit() {
		if (underAverageDeficit == null) {
			underAverageDeficit = getAreaFromBaseline(deficitAccessor, underAdjuster, getMeanDeficit());
		}
		return underAverageDeficit;
	}
	
	private Double underAverageRequest = null;
	private double getUnderAverageRequest() {
		if (underAverageRequest == null) {
			underAverageRequest = getAreaFromBaseline(requestAccessor, underAdjuster, getMeanNeighborhoodRequest());
		}
		return underAverageRequest;
	}
	
//	private Double underSeaRequest = null;
//	private double getUnderSeaRequest() {
//		if (underSeaRequest == null) {
//			underSeaRequest = getAreaFromBaseline(requestAccessor, underExpAdjuster, getRequestSeaLevel());
//		}
//		return underSeaRequest;
//	}
	
	private Double overAverageDeficit = null;
	private double getOverAverageDeficit() {
		if (overAverageDeficit == null) {
			overAverageDeficit = getAreaFromBaseline(deficitAccessor, overAdjuster, getMeanDeficit());
		}
		return overAverageDeficit;
	}
	
	private Double overAverageRequest = null;
	private double getOverAverageRequest() {
		if (overAverageRequest == null) {
			overAverageRequest = getAreaFromBaseline(requestAccessor, overAdjuster, getMeanNeighborhoodRequest());
		}
		return overAverageRequest;
	}

	private Double localUnderAverageDeficit = null;
	private double getLocalUnderAverageDeficit() {
		if (localUnderAverageDeficit == null) {
			localUnderAverageDeficit = getLocalAreaFromBaseline(deficitAccessor, underAdjuster, getMeanDeficit());
		}
		return localUnderAverageDeficit;
	}
	
	private Double localUnderAverageRequest = null;
	private double getLocalUnderAverageRequest() {
		if (localUnderAverageRequest == null) {
			localUnderAverageRequest = getLocalAreaFromBaseline(requestAccessor, underAdjuster, getMeanNeighborhoodRequest());
		}
		return localUnderAverageRequest;
	}
	
//	private Double localUnderSeaRequest = null;
//	private double getLocalUnderSeaRequest() {
//		if (localUnderSeaRequest == null) {
//			localUnderSeaRequest = getLocalAreaFromBaseline(requestAccessor, underAdjuster, getRequestSeaLevel());
//		}
//		return localUnderSeaRequest;
//	}
	
	private Double localOverAverageDeficit = null;
	private double getLocalOverAverageDeficit() {
		if (localOverAverageDeficit == null) {
			localOverAverageDeficit = getLocalAreaFromBaseline(deficitAccessor, overAdjuster, getMeanDeficit());
		}
		return localOverAverageDeficit;
	}
	
	private Double localOverAverageRequest = null;
	private double getLocalOverAverageRequest() {
		if (localOverAverageRequest == null) {
			localOverAverageRequest = getLocalAreaFromBaseline(requestAccessor, overAdjuster, getMeanNeighborhoodRequest());
		}
		return localOverAverageRequest;
	}
	
//	private Double localOverSeaRequest = null;
//	private double getLocalOverSeaRequest() {
//		if (localOverSeaRequest == null) {
//			localOverSeaRequest = getLocalAreaFromBaseline(requestAccessor, overAdjuster, getRequestSeaLevel());
//		}
//		return localOverSeaRequest;
//	}
	
//	private Double localOverMinRequest = null;
//	private double getLocalOverMinRequest() {
//		if (localOverMinRequest == null) {
//			localOverMinRequest = getLocalAreaFromBaseline(requestAccessor, overAdjuster, neighborhoodRequestWindow.getPeriodMin());
//		}
//		return localOverMinRequest;
//	}

	private Double dynamicUnderAverageDeficit = null;
	private double getDynamicUnderAverageDeficit() {
		if (dynamicUnderAverageDeficit == null) {
			dynamicUnderAverageDeficit = getDynamicAreaFromBaseline(deficitAccessor, underAdjuster, getMeanDeficit());
		}
		return dynamicUnderAverageDeficit;
	}
	
	private Double dynamicOverAverageDeficit = null;
	private double getDynamicOverAverageDeficit() {
		if (dynamicOverAverageDeficit == null) {
			dynamicOverAverageDeficit = getDynamicAreaFromBaseline(deficitAccessor, overAdjuster, getMeanDeficit());
		}
		return dynamicOverAverageDeficit;
	}
	
	private double getAreaFromBaseline(IListAccessor window, ISimpleAdjuster adjuster, double baseline) {
		double sum = 0.0;
		for (int future = 0; future < foresight; future++) {
			sum += adjuster.adjust(baseline - window.get(future));
		}
		return sum;
	}
	
	private double getLocalAreaFromBaseline(IListAccessor window, ISimpleAdjuster adjuster, double baseline) {
		double sum = 0.0, value;
		int future = 0;
		while ((value = adjuster.adjust(baseline - window.get(future))) > 0 && future < foresight) {
			sum += value;
			future++;
		}
		return sum;
	}
	
	private double getDynamicAreaFromBaseline(IListAccessor window, ISimpleAdjuster adjuster, double baseline) {
		double sum = 0.0;
		double max = 0.0;
		for (int future = 0; future < foresight; future++) {
			sum += adjuster.adjust(baseline - window.get(future));
			if (sum > max) {
				max = sum;
			}
		}
		return max;
	}
	
	private static final ISimpleAdjuster underAdjuster = new ISimpleAdjuster() {
		@Override
		public double adjust(double value) {
			return value > 0 ? value : 0.0;
		}
	};
	
	private static final ISimpleAdjuster overAdjuster = new ISimpleAdjuster() {
		@Override
		public double adjust(double value) {
			return value < 0 ? -value : 0.0;
		}
	};
	
//	private static final ISimpleAdjuster underPolyAdjuster = new ISimpleAdjuster() {
//		private static final double power = 2.0;
//		@Override
//		public double adjust(double value) {
//			return Math.pow(value > 0 ? value : 0, power);
//		}
//	};
//	
//	private static final ISimpleAdjuster overPolyAdjuster = new ISimpleAdjuster() {
//		private static final double power = 2.0;
//		@Override
//		public double adjust(double value) {
//			return Math.pow(value < 0 ? -value : 0, power);
//		}
//	}; 
	
	private static final ISimpleAdjuster underExpAdjuster = new ISimpleAdjuster() {
		@Override
		public double adjust(double value) {
			return value > 0 ? Math.exp(Math.pow(value, 2)) - 1 : 0;
		}
	};
//	
//	private static final ISimpleAdjuster overExpAdjuster = new ISimpleAdjuster() {
//		@Override
//		public double adjust(double value) {
//			return value < 0 ? Math.exp(Math.pow(value, 2)) - 1 : 0;
//		}
//	}; 
	
	public final IListAccessor deficitAccessor = new IListAccessor() {
		@Override
		public double get(int index) {
			return getPredictedDeficit(index);
		}
	};
	
	public final IListAccessor requestAccessor = new IListAccessor() {
		@Override
		public double get(int index) {
			return getPredictedNeighborhoodRequest(index);
		}
	};
	
	public double getRequestSeaLevel() {
		return 0;
	}
	
//	private final static double error = 0.001;
//	private Double requestSeaLevel = null;
//	public double getRequestSeaLevel() {
//		if (requestSeaLevel == null) {
//			double min = neighborhoodRequestWindow.getPeriodMin();
//			double max = neighborhoodRequestWindow.getPeriodMax();
//			double upperGuess = max;
//			double lowerGuess = min;
//			requestSeaLevel = max;
//			
//			double need = Clamp.getDefault().adjust(neighborhoodRequestWindow.getRecentSum() - min * neighborhoodRequestWindow.getRecentSize());
//			double upperNeed = need + error;
//			double lowerNeed = need - error;
//			
//			do {
//				double underBaseline = getAreaFromBaseline(requestAccessor, underAdjuster, requestSeaLevel);
//				
//				if (underBaseline < lowerNeed) {
//					if (requestSeaLevel < max) {
//						lowerGuess = requestSeaLevel;
//					} else {
//						break;
//					}
//				} else if (underBaseline > upperNeed) {
//					upperGuess = requestSeaLevel;
//				} else {
//					break;
//				}
//				requestSeaLevel = (upperGuess + lowerGuess) / 2;
//			} while(true);
//		}
//		return requestSeaLevel;
//	}
//	
//	private double constant() {
//		double need = Clamp.getDefault().adjust(neighborhoodRequestWindow.getRecentSum() - neighborhoodRequestWindow.getPeriodMin() * neighborhoodRequestWindow.getRecentSize());
//		double exp = getAreaFromBaseline(requestAccessor, underExpAdjuster, getRequestSeaLevel());
//		if (exp <= 0) {
//			System.err.println("exp too low: " + exp);
//		}
//		return exp > 0 ? need / exp : 1;
//	}

	// end of computations section

	/*
	 * Start of helper method section
	 */
	private double getAvailablePower(Entity[] sourceList, double request) {
		double available = 0;
		for (Entity entity : sourceList) {
			if (request <= 0)
				return available;
			double power = 0;
			switch (entity) {
			case Generation:
				power = getGenerationAvailability(request);
				break;
			case Grid:
				power = getBuyingFromGridAvailability(request);
				break;
			case Neighbors:
				power = getBuyingFromNeighborsAvailability(request);
				break;
			case Storage:
				power = getStoragePowerAvailability(request);
				break;
			}
			request -= power;
			available += power;
		}
		return available;
	}

	private double spendAvailablePower(Entity[] sourceList, double request) {
		double supplied = 0;
		for (Entity entity : sourceList) {
			if (request <= 0)
				return supplied;
			double power = 0;
			switch (entity) {
			case Generation:
				power = spendGeneratedPower(request);
				break;
			case Grid:
				power = buyFromGrid(request);
				break;
			case Neighbors:
				power = buyFromNeighbors(request);
				break;
			case Storage:
				power = spendStoredPower(request);
				break;
			}
			request -= power;
			supplied += power;
		}
		return supplied;
	}

	/**
	 * @param power
	 *            the amount of load to satisfy
	 * @return the power which was used
	 */
	private double satisfyLoad(double power) {
		double usedPower;
		if (power < getCurrentUnsatisfiedLoad()) {
			usedPower = power;
		} else {
			usedPower = getCurrentUnsatisfiedLoad();
		}
		currentlySatisfiedLoad += usedPower;
		return usedPower;
	}

	/**
	 * @param power
	 *            the amount of suspended load to satisfy
	 * @return the power which was used
	 */
	private double satisfySuspendedLoad(double power) {
		double usedPower;
		if (power < getCurrentSuspendedLoad()) {
			usedPower = power;
		} else {
			usedPower = getCurrentSuspendedLoad();
		}
		currentRemovedSuspension += usedPower;
		return usedPower;
	}

	private double forcefullySuspendLoad(double power) {
		double load = satisfyLoad(power);
		currentAddedSuspension += load;
		currentForcefullySuspendedLoad += load;
		return load;
	}

	/**
	 * @param power
	 *            the amount of power to suspend
	 * @return the amount of power suspended
	 */
	private double suspendLoad(double power) {
		if (SmartGridBuilder.getCanSuspendLoad()) {
			if (getCurrentRemainingSuspendableLoad() < power) {
				power = getCurrentRemainingSuspendableLoad();
			}

			double load = satisfyLoad(power);
			currentAddedSuspension += load;
			return load;
		} else {
			return 0;
		}
	}

	/**
	 * @param power
	 *            the amount of power to dump
	 * @return the amount of power dumped
	 */
	private double dumpPower(double power) {
		currentDumpedPower += power;
		return power;
	}

	/**
	 * @param power
	 * @return the power which was used
	 */
	private double getGenerationAvailability(double power) {
		double available = power;
		if (power > getCurrentRemainingGeneration()) {
			available = getCurrentRemainingGeneration();
		}
		return available;
	}

	/**
	 * @param power
	 * @return the power which was used
	 */
	private double spendGeneratedPower(double power) {
		double usedPower = power;
		if (power > getCurrentRemainingGeneration()) {
			usedPower = getCurrentRemainingGeneration();
		}
		currentlySpentGeneration += usedPower;
		return usedPower;
	}

	private double getStoragePowerAvailability(double power) {
		double available = power;
		if (storage.getCurrentPower() < power) {
			available = storage.getCurrentPower();
		}
		return available;
	}

	private double personalStoredPower;
	private double spendStoredPower(double power) {
		power = storage.draw(power);
		personalStoredPower += power;
		return power;
	}

	// End of helper methods section

	/*
	 * Value methods
	 */
	private double getCurrentUnsatisfiedLoadAvailability(double power) {
		if (power > getCurrentUnsatisfiedLoad())
			return getCurrentUnsatisfiedLoad();
		return power;
	}

	private double getCurrentUnsatisfiedLoad() {
		return loadModel.getCurrentValue() - currentlySatisfiedLoad;
	}

	private double getCurrentRemainingGeneration() {
		return generationModel.getCurrentValue() - currentlySpentGeneration;
	}

	private double getCurrentDeficit() {
		return getCurrentUnsatisfiedLoad() - getCurrentRemainingGeneration();
	}

	private double getCurrentSuspendedLoad() {
		return suspendedLoad + getCurrentChangeInSuspention();
	}

	// End of value methods section

	@Override
	public String toString() {
		return group.getName() + "-" + id;
	}

	@Override
	public String description() {
		return this.description(0);
	}

	@Override
	public String description(int nestingLevel) {
		String tabbing = "";
		if (nestingLevel > 0)
			tabbing = new String(new char[nestingLevel]).replace("\0", "\t");
		String str = "Agent: {\n\t" + tabbing + "id: " + id + "\n\t" + tabbing + "foresight: " + foresight + "\n\t" + tabbing + "generationModel: " + (generationModel == null ? "null\n" : generationModel.description(nestingLevel + 1)) + "\t" + tabbing + "loadModel: " + (loadModel == null ? "null\n" : loadModel.description(nestingLevel + 1)) + "\t" + tabbing + "storage: " + (storage == null ? "null\n" : storage.description(nestingLevel + 1)) + "\t" + tabbing + "suspendableModel: " + (suspendableModel == null ? "null\n" : suspendableModel.description(nestingLevel + 1)) + tabbing + "}\n";
		return str;
	}

	/*
	 * Windows
	 */
	public RunningMean getDemandWindow() {
		return demandWindow;
	}

	public double getMaxDemandOfPeriod() {
		return demandWindow.getRecentMax();
	}

	// end of windows

	public double getMeanLoadSeed() {
		return loadModel.getMeanSeedValue(); // this should not be NaN
	}

	/*
	 * Start of state
	 */
	public double getCurrentPowerAddedToStore() {
		return storage.getCurrentPowerAdded();
	}

	public double getCurrentPowerRemovedFromStore() {
		return storage.getCurrentPowerRemoved();
	}

	public double getCurrentStoredPower() {
		return storage.getCurrentPower();
	}

	public double getStoredPower() {
		return storage.getPower();
	}

	public double getStorageCapacity() {
		return storage.getCapacity();
	}

	public double getLoad() {
		return loadModel.getCurrentValue();
	}

	public double getGeneration() {
		return generationModel.getCurrentValue();
	}

	public double getSuspendableLoad() {
		if (SmartGridBuilder.getCanSuspendLoad())
			return suspendableModel.getCurrentValue() * getLoad();
		return 0;
	}

	public double getCurrentRemainingSuspendableLoad() {
		return getSuspendableLoad() - getCurrentChangeInSuspention();
	}

	public double getCurrentAddedSuspension() {
		return currentAddedSuspension;
	}

	public double getCurrentRemovedSuspension() {
		return currentRemovedSuspension;
	}

	public double getCurrentChangeInSuspention() {
		return currentAddedSuspension - currentRemovedSuspension;
	}

	public double getSuspendedLoad() {
		return suspendedLoad;
	}

	public double getCurrentForcefullySuspendedLoad() {
		return currentForcefullySuspendedLoad;
	}
	
	public double getUnsatisfiedLoadCount() {
		return currentForcefullySuspendedLoad > 0 ? 1 : 0; 
	}

	public double getCurrentDumpedPower() {
		return currentDumpedPower;
	}

	// end of internal state

	/*
	 * Power accounts
	 */
	public double getCurrentPowerBoughtFromGrid() {
		return currentPowerBoughtFromGrid;
	}

	public double getCurrentPowerBoughtFromNeighbors() {
		return currentPowerBoughtFromNeighbors;
	}

	public double getCurrentPowerSoldToGrid() {
		return currentPowerSoldToGrid;
	}

	public double getCurrentPowerSoldToNeighbors() {
		return currentPowerSoldToNeighbors;
	}

	// end of power accounts

	/*
	 * Monetary accounts
	 */
	public double getCurrentDebitToGrid() {
		return currentDebitToGrid;
	}

	public double getCurrentDebitToNeighbors() {
		return currentDebitToNeighbors;
	}

	public double getCurrentCreditFromGrid() {
		return currentCreditFromGrid;
	}

	public double getCurrentCreditFromNeighbors() {
		return currentCreditFromNeighbors;
	}

	public double getCurrentProfitFromNeigbors() {
		return currentCreditFromNeighbors - currentDebitToNeighbors;
	}

	public double getCurrentProfitFromGrid() {
		return currentCreditFromGrid - currentDebitToGrid;
	}

	public double getCurrentProfit() {
		return getCurrentProfitFromGrid() + getCurrentProfitFromNeigbors() - getCurrentGenerationCost() - getCurrentStorageCost();
	}

	// end of monetary accounts

	/*
	 * Start of cost functions
	 */
	public double getCurrentGenerationCost() {
		return currentGenerationCost;
	}

	public double getCurrentStorageCost() {
		return currentStorageCost;
	}

	// End of costs

	// FIX NOW
	public double getCurrentHomeElectricityRate() {
		return getCurrentProfit() / (0.001 + getCurrentChangeInSuspention() - getLoad());
	}

	/*
	 * Deficits
	 */
	public double getDeficit() {
		return loadModel.getCurrentValue() - generationModel.getCurrentValue();
	}

	public double getPredictedDeficit(int foresight) {
		if (foresight == 0) {
			return getDeficit();
		} else {
			return deficitWindow.getPeriodMean(foresight);
		}
	}

	public double getMeanDeficit() {
		return deficitWindow.getWindowMean();
	}

	public double getCurrentDemand() {
		return currentPowerBoughtFromGrid - currentPowerSoldToGrid;
	}

	// end of demand

	/*
	 * Neighborhood State
	 */
	public double getCurrentNeighborhoodRequest() {
		return currentNeighborhoodRequest;
	}
	
	public String getCurrentNeighborhoodRequestLabel() {
		return "Current Neighborhood Request of " + group.toString();
	}

	public double getPredictedNeighborhoodRequest() {
		return neighborhoodRequestWindow.getPeriodMean(0);
	}
	
	public String getPredictedNeighborhoodRequestLabel() {
		return "Predicted Neighborhood Request of " + group.toString();
	}

	private double getPredictedNeighborhoodRequest(int foresight) {
		return neighborhoodRequestWindow.getPeriodMean(foresight);
	}

	public double getMeanNeighborhoodRequest() {
		return neighborhoodRequestWindow.getWindowMean();
	}
	
	public String getMeanNeighborhoodRequestLabel() {
		return "Mean Neighborhood Request of " + group.toString();
	}
	
	public String getRequestSeaLevelLabel() {
		return "Request Sea Level of " + group.toString();
	}
	// end of neighborhood state

	public boolean isSmart() {
		return smart;
	}
	
	private ArrayList<Neighbor> neighborhood;
	private boolean hasBeenOrdered;
	
	private ArrayList<Neighbor> getNeighborhood() {
		if (neighborhood == null) {
			neighborhood = new ArrayList<Neighbor>(SmartGridBuilder.getNetwork().getDegree(this));
			for (Object obj:SmartGridBuilder.getNetwork().getAdjacent(this)) {
				if (obj instanceof Agent) {
					neighborhood.add(new Neighbor((Agent)obj, 0));
				}
			}
			Collections.sort(neighborhood);
			hasBeenOrdered = true;
		}
		
		if (!hasBeenOrdered) {
			if(SmartGridBuilder.getOrderNeighbors()) {
				Collections.sort(neighborhood);
			} else {
				HelperFunctions.randomizeList(neighborhood);
			}
			hasBeenOrdered = true;
		}
		
		return neighborhood;
	}
}

class Neighbor implements Comparable<Neighbor> {
	private Agent agent;
	private double value;
	
	public Neighbor(Agent agent, double value) {
		this.agent = agent;
		this.value = value;
	}
	
	@Override
	public int compareTo(Neighbor o) {
		// TODO Auto-generated method stub
		if (this.value > o.value) {
			return 1;
		} else if (this.value < o.value) {
			return -1;
		} else { 
			return 0;
		}
	}

	public Agent getAgent() {
		return agent;
	}

	public void setAgent(Agent agent) {
		this.agent = agent;
	}

	public double getValue() {
		return value;
	}

	public void setValue(double value) {
		this.value = value;
	}	
}
