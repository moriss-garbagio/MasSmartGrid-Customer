package power.components;

import java.util.LinkedList;

import org.w3c.dom.Node;

import power.SmartGridBuilder;
import power.components.generators.AgentGenerator;
import power.distributions.IRandomDistribution;
import power.distributions.RandomDistribution;
import power.helpers.AdjustedRunningMax;
import power.helpers.RunningMean;
import power.helpers.XmlTools;
import power.helpers.Computations;
import power.models.IRandomModel;
import power.models.RandomModel;
import power.tools.Absolute;
import power.tools.Amplify;
import power.tools.IDescribable;
import repast.simphony.engine.schedule.ScheduledMethod;
import repast.simphony.essentials.RepastEssentials;

/**
 * @author That Note: Make sure the RandomModel being used generates valid
 *         values. Negative prices are not expected.
 */
public class Grid implements IDescribable {

	private enum XmlNode {
		PowerRateRandomModel, PriceAdjustment, BlackoutRadius
	};

	// simulation members
	private final IReliability reliability;
	private final IRandomDistribution blackoutRadius;
	private final LinkedList<Agent> blackoutArea;

	private final IRandomModel randomModel;
	private final double buyingPriceAdjustment;
	private final double sellingPriceAdjustment;

	// factors
	private final AdjustedRunningMax demandWindow;
	private final RunningMean outflowWindow;
	private final RunningMean priceWindow;
	private double loadFactor;
	// private double diversityFactor;

	// private Double basePriceHistory;
	private Double loadFactorHistory;
	// private Double diversityFactorHistory;

	// power
	private double currentBoughtPower;
	private double currentSoldPower;

	// accounting
	private double currentDebit;
	private double currentCredit;
	
//	private double predictedOutflowVisual;
//	private double predictedBasePriceVisual;
//	private double predictedMeanBasePriceVisual;

	public static Grid create(Node xml) {
		IReliability reliability = Reliability.create(xml);
		IRandomDistribution blackoutRadius = RandomDistribution.create(XmlTools.getUptoOneNode(xml, XmlNode.BlackoutRadius));

		IRandomModel randomModel = RandomModel.create(XmlTools.getExactlyOneNode(xml, XmlNode.PowerRateRandomModel));
		double priceAdjustment = Double.parseDouble(XmlTools.getAttributeValue(XmlTools.getExactlyOneNode(xml, XmlNode.PriceAdjustment), XmlTools.XmlAttribute.value));
		return new Grid(randomModel, blackoutRadius, reliability, priceAdjustment);
	}

	private Grid(IRandomModel randomModel,
			IRandomDistribution blackoutRadius,
			IReliability reliability,
			double priceAdjustment) {
		this.randomModel = randomModel;
		if (reliability == null || blackoutRadius == null) {
			this.reliability = BlankReliability.getBlankReliability();
		} else {
			this.reliability = reliability;
		}
		this.blackoutRadius = blackoutRadius;
		this.blackoutArea = new LinkedList<Agent>();

		this.randomModel.getModel().setSource(this);

		this.buyingPriceAdjustment = 1.0 - priceAdjustment;
		this.sellingPriceAdjustment = 1.0 + priceAdjustment;

		demandWindow = new AdjustedRunningMax(Absolute.getSingleton());
		outflowWindow = new RunningMean();
		priceWindow = new RunningMean(SmartGridBuilder.getWindowSize());
	}

	/*
	 * Pricing methods
	 */
	public double getCurrentBasePrice() {
		return randomModel.getCurrentValue();
	}

	public String getCurrentBasePriceLabel() {
		return "Current base price";
	}

	public double getMeanBasePrice() {
		return priceWindow.getWindowMean();
		// return basePriceHistory;
	}
	
//	public double getMeanBasePriceVisual() {
//		return predictedMeanBasePriceVisual;
//	}

	public String getMeanBasePriceLabel() {
		return "Mean base price";
	}

	public double getPredictedBasePrice() {
		return priceWindow.getPeriodMean(0);
	}
	
//	public double getPredictedBasePriceVisual() {
//		return predictedBasePriceVisual;
//	}

	public String getPredictedBasePriceLabel() {
		return "Predicted base price";
	}

	public double getPredictedBasePrice(int future) {
		return priceWindow.getPeriodMean(future);
	}

	public double getCurrentBuyingPrice() {
		return randomModel.getCurrentValue() * buyingPriceAdjustment;
	}

	public String getCurrentBuyingPriceLabel() {
		return "Grid's buying price";
	}

	public double getCurrentSellingPrice() {
		return randomModel.getCurrentValue() * sellingPriceAdjustment;
	}

	public String getCurrentSellingPriceLabel() {
		return "Grid's selling price";
	}

	// end of pricing methods

	public double getPredictedOutflow() {
		return outflowWindow.getPeriodMean(0);
	}

	public String getPredictedOutflowLabel() {
		return "Predicted outflow";
	}

	public double getAvgPredictedDemandByPeriod() {
		return getAvgPredictedDemandByPeriod(0);
	}

	public double getAvgPredictedDemandByPeriod(int foresight) {
		return demandWindow.getPeriodMean(foresight);
	}

	private Double normalPredictedDemandByPeriod = null; 
	public double getNormalPredictedDemandByPeriod() {
		if (normalPredictedDemandByPeriod == null) {
			normalPredictedDemandByPeriod = getNormalPredictedDemandByPeriod(0);
		}
		return normalPredictedDemandByPeriod;
	}
	
	public String getNormalPredictedDemandByPeriodLabel() {
		return "Normal Predicted Demand";
	}

	public double getNormalPredictedDemandByPeriod(Integer foresight) {
		double currentDemandSum = 0;
		for (AgentGenerator agentGen : SmartGridBuilder.getAgentGeneratorMap().values()) {
			currentDemandSum += agentGen.getPredictedDemandByPeriod(foresight);
		}
		
		return currentDemandSum / SmartGridBuilder.getSimulationConstant();
	}

	/*
	 * Transaction methods
	 */
	/**
	 * @param power
	 *            The amount of power requested to be exchanged. If this amount
	 *            is negative then power is bought from the grid otherwise it is
	 *            sold to the grid.
	 * @return The amount of money traded during the exchange. If this value is
	 *         negative then the exchange costed the exchange requester money.
	 */
	public double exchange(double power) {
		if (power < 0) {
			return buyFromGrid(power);
		} else {
			return sellToGrid(power);
		}
	}

	/**
	 * @param power
	 *            The amount of power requested to be bought from the grid
	 * @return The amount of power not bought by the grid
	 */
	public double buyFromGrid(double power) {
		double soldPower = power;
		currentSoldPower += soldPower;
		currentCredit += getSellingValue(soldPower);
		return soldPower;
	}

	public double buyFromGridAvailability(double power) {
		return power;
	}

	public double getSellToGridAvailability(double power) {
		// System.err.println("+getSellToGridAvailability: power=" + power);
		// System.err.println("currentBought: " + currentBoughtPower);
		// System.err.println("outflow: " + getPredictedOutflow());
		// System.err.println("buyback: " +
		// SmartGridBuilder.getHourlyGridBuyBack());

		if (currentBoughtPower >= SmartGridBuilder.getHourlyGridBuyBack() || currentBoughtPower >= getPredictedOutflow())
			return 0.0;

		if (currentBoughtPower + power > SmartGridBuilder.getHourlyGridBuyBack()) {
			power = SmartGridBuilder.getHourlyGridBuyBack() - currentBoughtPower;
		}

		if (currentBoughtPower + power > getPredictedOutflow()) {
			power = getPredictedOutflow() - currentBoughtPower;
		}

		// System.err.println("-getSellToGridAvailability: available=" + power);

		return power;
	}

	/**
	 * @param power
	 *            The amount of power requested to be sold to the grid
	 * @return The amount of money provided for the power
	 */
	public double sellToGrid(double power) {
		double boughtPower = getSellToGridAvailability(power);
		currentBoughtPower += boughtPower;
		currentDebit += getBuyingValue(boughtPower);
		return boughtPower;
	}

	// end of transaction methods

	@ScheduledMethod(start = 0, interval = 1, priority = 3)
	public void initialize() {
		// if (SmartGridBuilder.getEndSimulationTick() > 0 &&
		// RepastEssentials.GetTickCount() >=
		// SmartGridBuilder.getEndSimulationTick()) {
		// System.exit(0);
		// }
		if (!blackoutArea.isEmpty() && reliability.isOperational()) {
			// System.out.println("-Exiting Blackout: " +
			// RepastEssentials.GetTickCount());
			// System.out.println();

			// end blackout
			for (Agent agent : blackoutArea) {
				agent.reconnectToGrid();
			}
			blackoutArea.clear();
		} else if (blackoutArea.isEmpty() && !reliability.isOperational()) {
			// System.out.println("+Entering Blackout: " +
			// RepastEssentials.GetTickCount());
			startBlackout();
			if (SmartGridBuilder.getPauseOnBlackout()) {
				RepastEssentials.PauseSimulationRun();
			}
			// for (Agent agent : blackoutArea) {
			// System.out.print(agent.toString() + ",");
			// }
			// System.out.println();
			// System.out.println();
		}
		
		normalPredictedDemandByPeriod = null;

		currentCredit = 0;
		currentDebit = 0;
		currentBoughtPower = 0;
		currentSoldPower = 0;

		// basePriceHistory =
		// Computations.getExponentialAverage(basePriceHistory,
		// getCurrentBasePrice());
		
//		predictedOutflowVisual = outflowWindow.getPeriodMean(0);
//		predictedBasePriceVisual = priceWindow.getPeriodMean(0);
//		predictedMeanBasePriceVisual = priceWindow.getWindowMean();
	}

	private void startBlackout() {
		for (Object obj1 : SmartGridBuilder.getContext().getRandomObjects(Agent.class, 1)) {
			LinkedList<Agent> innerNeighbors = new LinkedList<Agent>();
			LinkedList<Agent> outerNeighbors = new LinkedList<Agent>();

			Agent agent = (Agent) obj1;
			agent.disconnectFromGrid();
			blackoutArea.add(agent);
			innerNeighbors.add(agent);

			int radius = blackoutRadius.nextInt();
			for (int current = 0; current < radius; current++) {
				while (!innerNeighbors.isEmpty()) {
					agent = innerNeighbors.removeFirst();
					for (Object obj2 : SmartGridBuilder.getNetwork().getAdjacent(agent)) {
						if (obj2.getClass() == Agent.class) {
							Agent neighbor = (Agent) obj2;
							if (neighbor.isConnectedToGrid()) {
								neighbor.disconnectFromGrid();
								blackoutArea.add(neighbor);
								outerNeighbors.add(neighbor);
							}
						}
					}
				}
				if (outerNeighbors.isEmpty()) {
					return;
				} else {
					LinkedList<Agent> neighbors = innerNeighbors;
					innerNeighbors = outerNeighbors;
					outerNeighbors = neighbors;
				}
			}
		}
	}

	@ScheduledMethod(start = 0, interval = 1, priority = -3)
	public void finalize() {
		demandWindow.add(getCurrentDemand());
		outflowWindow.add(getCurrentSoldPower());
		priceWindow.add(getCurrentBasePrice());
		
		double max = demandWindow.getRecentMax();
		double mean = demandWindow.getRecentMean();
		if (max == 0) {
			loadFactor = 1;
		} else {
			loadFactor = Math.abs(mean / max);
		}

		loadFactorHistory = Computations.getExponentialAverage(loadFactorHistory, loadFactor);
	}

	public RunningMean getPriceWindow() {
		return priceWindow;
	}

	public double getMeanLoadFactor() {
		return loadFactorHistory;
	}

	public String getMeanLoadFactorLabel() {
		return "Mean Load Factor";
	}

	@Override
	public String toString() {
		return "The Grid";
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
		String str = "Grid: {\n\t" + tabbing + "buyingPriceAdjustment: " + buyingPriceAdjustment + "\n\t" + tabbing + "sellingPriceAdjustment: " + sellingPriceAdjustment + "\n\t" + tabbing + "randomModel: " + randomModel + "\n" + tabbing + "}\n";
		return str;
	}

	public double getBaseValue(double power) {
		return power * getCurrentBasePrice();
	}

	public double getTradeValue(double power) {
		if (power < 0) {
			return getSellingValue(power);
		} else {
			return getBuyingValue(power);
		}
	}

	public double getSellingValue(double power) {
		return power * getCurrentSellingPrice();
	}

	public double getBuyingValue(double power) {
		return power * getCurrentBuyingPrice();
	}

	/*
	 * Statistical properties
	 */
	public double getLoadFactor() {
		return loadFactor;
	}

	public String getLoadFactorLabel() {
		return "Load Factor";
	}

	// public double getDiversityFactor() {
	// return diversityFactor;
	// }

	public String getDiversityFactorLabel() {
		return "Diversity Factor";
	}

	public double getCurrentBoughtPower() {
		return currentBoughtPower;
	}

	public double getDemandSTD() {
		return demandWindow.recomputeStandardDev((int) SmartGridBuilder.getPeriod());
	}

	public String getDemandSTDLabel() {
		return "Demand standard deviation";
	}

	public double getDemandVariance() {
		return demandWindow.recomputeVariance((int) SmartGridBuilder.getPeriod());
	}

	public String getDemandVarianceLabel() {
		return "Demand Variation";
	}

	public double getDemandCV() {
		return demandWindow.recomputeStandardDev((int) SmartGridBuilder.getPeriod()) / Math.abs(demandWindow.getRecentMean());
	}

	public String getDemandCVLabel() {
		return "Demand coefficient of variation";
	}

	public String getCurrentBoughtPowerLabel() {
		return "Currently Bought Power";
	}

	public double getCurrentSoldPower() {
		return currentSoldPower;
	}

	public String getCurrentSoldPowerLabel() {
		return "Currently Sold Power";
	}

	public double getCurrentDebit() {
		return currentDebit;
	}

	public String getCurrentDebitLabel() {
		return "Current Debit To Homes";
	}

	public double getCurrentCredit() {
		return currentCredit;
	}

	public String getCurrentCreditLabel() {
		return "Current Credit From Homes";
	}

	public double getCurrentDemand() {
		return currentSoldPower - currentBoughtPower;
	}

	public String getCurrentDemandLabel() {
		return "Current Demand From Homes";
	}

	public double getPowerDemandSTD() {
		return demandWindow.recomputeStandardDev(SmartGridBuilder.getPeriod(), Amplify.getHandyInstance(1.0 / SmartGridBuilder.getAgentPopulation()));
	}

	public String getPowerDemandSTDLabel() {
		return "Standard Deviation of Demand ";
	}
}
