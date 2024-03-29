package entity;

import org.joda.time.LocalTime;

/**
 * Help class having exit time and moving_obj in order to rank them
 * 
 * @author xiaohui
 * 
 */
public class TimeObject implements Comparable {

	public LocalTime time;
	public MovingObject mo;

	public TimeObject(LocalTime time, MovingObject mo) {
		this.time = time;
		this.mo = mo;
	}

	/**
	 * order in descending order
	 */
	@Override
	public int compareTo(Object arg0) {
		if (arg0 instanceof TimeObject) {
			TimeObject e = (TimeObject) arg0;
			if (this.time.isAfter(e.time)) {
				return -1;
			} else if (this.time.isBefore(e.time)) {
				return 1;
			}
		}
		return 0;
	}

	public String toString() {
		return "TO Obj:" + time.toString() + " " + mo.toString();
	}
}
