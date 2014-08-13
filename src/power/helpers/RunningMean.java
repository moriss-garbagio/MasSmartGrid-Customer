package power.helpers;

import power.SmartGridBuilder;
import power.tools.CycleQueue;
import power.tools.IAdjuster;
import repast.simphony.essentials.RepastEssentials;

public class RunningMean {
	protected final double[] periodicSumList;
	protected double recentSum;
	protected double windowSum;
	protected Integer windowCapacity;

	protected final CycleQueue<Double> window;

	public RunningMean() {
		this(null);
	}
	
//	private boolean test = false;
//	public void setTest() {
//		test = true;
//	}

	public RunningMean(Integer windowCapacity) {
		this.windowCapacity = windowCapacity;
		window = new CycleQueue<Double>(getWindowCapacity() + 1);
		periodicSumList = new double[(int) SmartGridBuilder.getPeriod()];
		clearCache();
	}

	public void add(double value) {
//		if (test) {
//			System.out.println("add: " + value + ", init size: " + window.size());
//		}

		window.add(value);

		periodicSumList[getPeriod()] += value;

		if (window.size() > SmartGridBuilder.getPeriod()) {
			recentSum += value - window.get(window.size() - SmartGridBuilder.getPeriod() - 1);
		} else {
			recentSum += value;
		}

		windowSum += value;

//		if (test) {
//			System.out.println("cap: " + getWindowCapacity() + "fin size: " + window.size());
//			System.out.println("init period vals:");
//			for (int i = 0; i < periodicSumList.length; i++) {
//				System.out.println("ind: " + i + ", val: " + periodicSumList[i]);
//			}
//		}
		
		while (window.size() > getWindowCapacity()) {
			value = window.remove();
			if (window.size() < SmartGridBuilder.getPeriod()) {
				recentSum -= value;
			}

			periodicSumList[(SmartGridBuilder.getPeriod() + (getPeriod() - window.size()) % SmartGridBuilder.getPeriod()) % SmartGridBuilder.getPeriod()] -= value;
			windowSum -= value;
			
//			if (test) {
//				System.out.println("removed: " + value + ", size: " + window.size() + ", period ind: " + (SmartGridBuilder.getPeriod() + ((getPeriod() - window.size()) % SmartGridBuilder.getPeriod()) % SmartGridBuilder.getPeriod()));
//			}
		}
		
//		if (test) {
//			System.out.println("fin period vals:");
//			for (int i = 0; i < periodicSumList.length; i++) {
//				System.out.println("ind: " + i + ", val: " + periodicSumList[i]);
//			}
//		}
	}

	public double getPeriodMean(int foresight) {
		foresight = foresight % SmartGridBuilder.getPeriod();
		int numberOfValues = (window.size() + (foresight - SmartGridBuilder.getPeriod()) % SmartGridBuilder.getPeriod() - 1) / SmartGridBuilder.getPeriod() + 1;
		int index = (getPeriod() + foresight) % SmartGridBuilder.getPeriod();
		return periodicSumList[index] / numberOfValues;
	}

	public double getRecentSum() {
		return recentSum;
	}

	public double getRecentMean() {
		if (window.size() > 0) {
			if (window.size() < SmartGridBuilder.getPeriod()) {
				return recentSum / window.size();
			} else {
				return recentSum / SmartGridBuilder.getPeriod();
			}
		} else {
			return 0;
		}
	}

	public double getWindowSum() {
		return windowSum;
	}

	public double getWindowMean() {
		return windowSum / window.size();
	}

	public double getMean(int size) {
		if (size == SmartGridBuilder.getPeriod() || (window.size() < SmartGridBuilder.getPeriod() && size == window.size())) {
			return getRecentMean();
		} else if (size == getWindowCapacity() || (window.size() < getWindowCapacity() && size == window.size())) {
			return getWindowMean();
		} else {
			return recomputeSum(size) / Math.min(size, window.size());
		}
	}

	public double recomputeVariance(int size) {
		if (window.size() > 0 && size > 0) {
			double sum = 0;
			double mean = getMean(size);

			for (int index = window.size() > size ? window.size() - size : 0; index < window.size(); index++) {
				sum += Math.pow(window.get(index) - mean, 2);
			}
			return sum;
		} else {
			return 0;
		}
	}

	public double recomputeStandardDev(int size) {
		if (window.size() > 0 && size > 0) {
			return Math.sqrt(recomputeVariance(size) / (Math.min(size, window.size()) - 1));
		} else {
			return 0;
		}
	}

	public double recomputeStandardDev(int size, IAdjuster adjuster) {
		if (window.size() > 0 && size > 0) {
			double sum = 0;
			double mean = adjuster.adjust(getMean(size));

			for (int index = window.size() > size ? window.size() - size : 0; index < window.size(); index++) {
				sum += Math.pow(adjuster.adjust(window.get(index)) - mean, 2);
			}
			return Math.sqrt(sum / (Math.min(size, window.size()) - 1));
		} else {
			return 0;
		}
	}

	protected double recomputePeriodicSum(int foresight) {
		double sum = 0;
		for (int index = window.size() + (foresight - SmartGridBuilder.getPeriod()) % SmartGridBuilder.getPeriod() - 1; index >= 0; index -= (int) SmartGridBuilder.getPeriod()) {
			sum += window.get(index);
		}
		return sum;
	}

	protected double recomputeSum(int size) {
		double sum = 0;
		for (int index = window.size() > size ? window.size() - size : 0; index < window.size(); index++) {
			sum += window.get(index);
		}
		return sum;
	}

	protected void clearCache() {
		recentSum = 0;
		windowSum = 0;
		for (int index = 0; index < periodicSumList.length; index++) {
			periodicSumList[index] = 0;
		}
	}

	public CycleQueue<Double> getWindow() {
		return window;
	}

	public int getWindowSize() {
		return window.size();
	}

	public int getRecentSize() {
		if (window.size() < SmartGridBuilder.getPeriod()) {
			return window.size();
		} else {
			return SmartGridBuilder.getPeriod();
		}
	}

	public int getWindowCapacity() {
		if (windowCapacity == null) {
			return SmartGridBuilder.getWindowSize();
		} else {
			return windowCapacity;
		}
	}
	
	public int getPeriod() {
		return (int) RepastEssentials.GetTickCount() % SmartGridBuilder.getPeriod();
	}
	
	public Double getWindowValue(int index) {
		return window.get(index);
	}
	
	public double getPeriodSum(int index) {
		return periodicSumList[index];
	}
}
