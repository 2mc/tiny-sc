
+ Nil {
	receiveChuck { | chucker, fadeAction |
		("Ooops!  Someone tried to chuck to nil.  Probable causes:").postln;
		("1. No SynthTree has yet been created by chucking.").postln;
		("2. Trying to chuck into a SynthTree parameter that does not exist.").postln;
		"Current parameters are:".scatList(currentEnvironment.keys.asArray.sort).postln;
		^chucker;
	}
}

+ Symbol {
	receiveChuck { | chucker, replaceAction |
		//		^synthTree.asSynthTree.chuck(this, replaceAction);
		^this.asSynthTree.chuck(chucker, replaceAction)
	}
}

+ SynthTree {
	receiveChuck { | chucker, replaceAction |
		this.chuck(chucker, replaceAction);
	}
}

+ Ref {
	receiveChuck { | chucker |
		^PatternInstrument(chucker.asPatternPlayer, value.asPattern)
	}
}

+ Function {
	receiveChuck { | chucker | ^PatternPlayer(chucker.asPattern, this.asPattern) }

	asPattern { ^Pfunc(this) }
}

+ SimpleNumber {
	receiveChuck { | chucker |
		^PatternPlayer(chucker.asPattern, this)
	}
	
	asPattern { ^this }
}

+ Pattern {
	receiveChuck { | chucker | ^PatternPlayer(chucker.asPattern, this) }
	asPattern { ^this }
}

+ PatternPlayer { asPattern { ^this } }

+ SequenceableCollection { asPattern { ^Pseq(this, inf) } }