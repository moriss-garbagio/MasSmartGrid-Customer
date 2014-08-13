package power.helpers;

import java.util.List;

import repast.simphony.random.RandomHelper;

public class HelperFunctions {
	public static <T> void randomizeList(List<T> list) {
		for (int index = list.size() - 1; index >= 0; index--) {
			int random = RandomHelper.nextIntFromTo(0, index);
			T neighbor = list.get(random);
			if (random != index) {
				list.set(random, list.get(index));
				list.set(index, neighbor);
			}
		}
	}
}
