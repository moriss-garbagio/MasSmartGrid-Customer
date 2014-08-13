package power.tools;

public interface IAdjuster extends ISimpleAdjuster, IDescribable {
	public int adjust(int value);
}
