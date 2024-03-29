package power.helpers;

import java.util.Comparator;
import java.util.PriorityQueue;

import org.apache.commons.lang.NullArgumentException;

import power.SmartGridBuilder;
import power.tools.IAdjuster;
import repast.simphony.essentials.RepastEssentials;

public class AdjustedRunningMax extends RunningMean {

	protected final IAdjuster maxAdjuster;
	protected final PriorityQueue<Double> recentMaxHeap;
	
	public AdjustedRunningMax(IAdjuster maxAdjuster) {
		this(null, maxAdjuster);
	}

	public AdjustedRunningMax(Integer windowCapacity, IAdjuster maxAdjuster) {
		super(windowCapacity);
		recentMaxHeap = new PriorityQueue<Double>(getWindowCapacity() + 1, new Comparator<Double>() {
			@Override
			public int compare(Double o1, Double o2) {
				if (o1 > o2) {
					return -1;
				} else if (o1 < o2) {
					return 1;
				} else {
					return 0;
				}
			}
		});
		if (maxAdjuster == null) {
			throw new NullArgumentException("maxAdjuster");
		} else {
			this.maxAdjuster = maxAdjuster;
		}
	}

	@Override
	public void add(double value) {
		window.add(value);
		recentMaxHeap.add(maxAdjuster.adjust(value));

		periodicSumList[getPeriod()] += value;

		if (window.size() > SmartGridBuilder.getPeriod()) {
			double leaving = window.get(window.size() - SmartGridBuilder.getPeriod() - 1);
			recentSum += value - leaving;
			recentMaxHeap.remove(maxAdjuster.adjust(leaving));

		} else {
			recentSum += value;
		}

		windowSum += value;

		while (window.size() > getWindowCapacity()) {
			value = window.remove();
			if (window.size() < SmartGridBuilder.getPeriod()) {
				recentSum -= value;
				recentMaxHeap.remove(maxAdjuster.adjust(value));
			}

//			periodicSumList[(window.size() + getPeriod()) % SmartGridBuilder.getPeriod()] -= value;
			periodicSumList[(SmartGridBuilder.getPeriod() + (getPeriod() - window.size()) % SmartGridBuilder.getPeriod()) % SmartGridBuilder.getPeriod()] -= value;
			windowSum -= value;
		}
	}

	public double getRecentMax() {
		if (recentMaxHeap.isEmpty()) {
			return 0;
		} else {
			return recentMaxHeap.peek();
		}
	}
}
