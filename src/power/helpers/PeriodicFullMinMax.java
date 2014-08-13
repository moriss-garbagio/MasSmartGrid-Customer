package power.helpers;

import java.util.Comparator;
import java.util.PriorityQueue;

import power.SmartGridBuilder;
import repast.simphony.essentials.RepastEssentials;

public class PeriodicFullMinMax extends RunningMean {

	public PeriodicFullMinMax() {
		this(null);
	}
	
	public PeriodicFullMinMax(Integer windowCapacity) {
		super(windowCapacity);
	}

	@Override
	public void add(double value) {
		window.add(value);
		periodicSumList[getPeriod()] += value;
		
		if (window.size() > SmartGridBuilder.getPeriod()) {
			double leaving = window.get(window.size() - SmartGridBuilder.getPeriod() - 1);
			recentSum += value - leaving;
		} else {
			recentSum += value;
		}

		windowSum += value;

		while (window.size() > getWindowCapacity()) {
			value = window.remove();
			if (window.size() < SmartGridBuilder.getPeriod()) {
				recentSum -= value;
			}

			periodicSumList[(SmartGridBuilder.getPeriod() + (getPeriod() - window.size()) % SmartGridBuilder.getPeriod()) % SmartGridBuilder.getPeriod()] -= value;
			windowSum -= value;
		}
	}
//		maxMoment = -1;
//		minMoment = -1;
//	}
//
//	private int maxMoment = -1;
//	private double max;
//	public double getPeriodMax() {
//		if (maxMoment < 0) {
//			max = getPeriodMean(0);
//			maxMoment = 0;
//			for (int index = 1; index < SmartGridBuilder.getPeriod();index++) {
//				if (max < getPeriodMean(index)) {
//					max = getPeriodMean(index);
//					maxMoment = index;
//				}
//			}
//			return max;
//		} else {
//			return max;
//		}
//	}
//	
//	private int minMoment = -1;
//	private double min;
//	public double getPeriodMin() {
//		if (minMoment < 0) {
//			min = getPeriodMean(0);
//			minMoment = 0;
//			for (int index = 1; index < SmartGridBuilder.getPeriod();index++) {
//				if (min > getPeriodMean(index)) {
//					min = getPeriodMean(index);
//					minMoment = index;
//				}
//			}
//			return min;
//		} else {
//			return min;
//		}
//	}
}
