/*
Edef: Associate an EventPattern with a symbol and implement propagation 
of later modifications of the pattern to streams played from it. 
Cdef: clone of an Edef, inherits subsequent changes to father.
Idef: subclass of EventStreamPlayer, with inheritance.
Bdef: subclass of Idef, broadcasting instead of playing

These subclass of EventPattern and Idef subclass of EventStream.  
The reason is to implement the alternative asStream and embedInStream methods 
without having to add exra wrappers in a different class to handle them.

Note: Edef plays as Idef playing or broadcasting 
depending on whether it is chucking into a SynthTree or not. 


IZ Tue, Apr 22 2014, 00:42 EEST

a = Edef(\x, (degree: 20, dur: 1));
b = a.play;
b.inspect;
a.inspect;
c = a.broadcast;
c addDependant: { | ... args | args.postln; };
c.inspect;
delta

*/

Edef : EventPattern { // NamedEventPattern
	var <name;     // Can be nil
	var <children;

	//	*initClass { all = IdentityDictionary() }

	*new { | name, argPattern, propagate = false |
		var instance;
		instance = NameSpace(\Edef, name, { this.newCopyArgs((), name) });
		argPattern !? { instance.replace(argPattern, propagate) };
		^instance;
	}

	replace { | argPattern, propagate = false |
		event = argPattern;
		if (propagate) { this.propagate };
	}

	merge { | argPattern, propagate = false |
		// eventPattern = eventPattern.merge(argPattern);
		if (propagate) { this.propagate };
	}

	propagate {
		//		children do: _.inherit(eventPattern);
	}

	broadcast { | name |
		^this.play(name, true);
	}

	play { | name, broadcast = false |
		/* Creates Idef */
		var player;
		player = Idef(name, this);
		children = children add: player;
		if (broadcast) { player.initBroadcast };
		^player.play;
	}

	=> { | chuckee |
		// play into named Idef (if chuckee is Ref or Idef)
		// or into SynthTree (if chuckee is Symbol or SynthTree)
		^chuckee.receiveEdef(this);
	}

	=>> { | symbol |
		// clone into Cdef named after symbol
		this.clone(symbol);
	}

	clone { | name | ^Cdef(name, this) }

}

Cdef : Edef { // NamedEventPatternClone
	// clone of an Edef.  Inherits changes propagated by parent
	var <parent; // only for removing from parent upon request
	var <mods; // locally modified elements: apply these on inherited pattern

	//	*new { | name, parent | ^super.new(name, parent.eventPattern).initCdef(parent); }
	initCdef { | argParent | parent = argParent; }

	// uses new Event.merge method.  Other types of mods may 
	// do intelligent merging of patterns - as distinct from Events
	// Other types, as in: A function applied on the parent pattern.
	inherit { | argPattern |
		//	eventPattern = argPattern.merge(mods);
		this.propagate;
	}
	
	// TODO: Add methods for changing the EventPattern + Mods

}

+ Nil { merge { | parentPattern | ^parentPattern } }
+ Event {
	merge { | parentPattern |
		^parentPattern.mergeEvent(this);
	}
	mergeEvent { | modEvent |
		
	}

	mergeFunction { | modFunction |

	}
}

+ Function {
	merge { | parentPattern |
		^parentPattern.mergeFunction(this);
	}

}

+ Pattern {
	mergeEvent {

	}

	mergeFunction {

	}
}
