package power.helpers;

import java.util.Comparator;
import java.util.PriorityQueue;

import power.SmartGridBuilder;
import repast.simphony.essentials.RepastEssentials;

public class RunningMax extends RunningMean {

	protected final PriorityQueue<Double> recentMaxHeap;

	public RunningMax() {
		this(null);
	}
	
	public RunningMax(Integer windowCapacity) {
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
	}

	@Override
	public void add(double value) {
		window.add(value);
		recentMaxHeap.add(value);

		periodicSumList[getPeriod()] += value;

		if (window.size() > SmartGridBuilder.getPeriod()) {
			double leaving = window.get(window.size() - SmartGridBuilder.getPeriod() - 1);
			recentSum += value - leaving;
			recentMaxHeap.remove(leaving);

		} else {
			recentSum += value;
		}

		windowSum += value;

		while (window.size() > getWindowCapacity()) {
			value = window.remove();
			if (window.size() < SmartGridBuilder.getPeriod()) {
				recentSum -= value;
				recentMaxHeap.remove(value);
			}

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
