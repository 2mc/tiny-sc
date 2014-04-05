/*
Access and modify the structure of a tree of synths, with their busses and groups.
Each node of the tree holds a synth, and if present, the group, and input-output
control names and busses.

Each server has one root SynthTree, whose root branch contains the RootNode.

IZ Thu, Mar  6 2014, 21:51 EET
*/

SynthTree : IdentityTree {

	classvar <>showGuiAtStartup = true;
	classvar <default;
	classvar nameSpaces; // dictionaries holding the SynthTree instances by server
	classvar parentEvents; // contains parent events for each server. 
	/* Each parent event contains: 
		~fx: Last chucked SynthTree which has inputs
		~st: Last chucked SynthTree (or a SynthTree explicitly pushed)
		~dur: Default duration for playing patterns
		~fadeTime: Global default duration for fade-in and fade-out
		~numChans: Global default number of channels for audio input busses (default: 2)
	*/
	var <synth;  // the synth of this node
	var <>inputs; // dictionary of input names and bus specs or busses
	var <outputName; // name of input where this synth sends it output
	var <output; // SynthTree instance where this synth sends its output
	var >group;  // optional group enclosing synth as well as
	// synths of input subtree [!??!?]
	var <>template; // template for (re-) creating synth
    var <>notStopped = true; // if false, do not restart on initTree or
	// on chuck/replace
    var <>fadeTime;
	var <>name;
	var <>args; // TODO: args sent to synth at creation time
	var <>replaceAction = \fadeOut; // only used by addInputSynth

	*initClass {
		StartUp add: {
			parentEvents = IdentityDictionary();
			nameSpaces = MultiLevelIdentityDictionary();
			this.setServer(Server.default);
			Spec.specs.at(\amp).default = 0.1;
			Spec.specs.[\trigRate] = [0.1, 50, 'exp', 0, 1, ""].asSpec;
			if (showGuiAtStartup) { this.faders };
		}
	}

	*setServer { | server |
		var parentEvent;
		server ?? { server = Server.default };
		parentEvent = this.makeParentEvent(server);
		// TODO? Keep track of separate envir stacks for each server?
		().parent_(parentEvent).push; // we loose last ~st for this server
		default = this.new(\root);
		default.inputs = IdentityDictionary();
		default.inputs[\in] = Bus('audio', 0,
			// server.options.numOutputBusChannels,
			0, // trick the allocator: reserve 0 channels
			server);
		nameSpaces[server, \root] = default;
		ServerBootCheck add: { // most reliable way to check server boot
				default.group = server.asTarget;
				BufferFunc.initBuffers(server);
				{ BufferFunc.postBufNames }.defer(1);
				default.initTree(true);
				this.changed(\serverBooted, server);
		};
		^server;
	}

	*newName { | baseName = "st" | ^format ("%%", baseName, UniqueID.next) }

	*makeParentEvent { | argServer |
		var parentEvent;
		parentEvent = parentEvents[argServer];
		parentEvent ?? {
			parentEvent = (dur: 1/3, fadeTime: 0.1, numChans: 2);
			parentEvents[argServer] = parentEvent;
		};
		^parentEvent;
	}

	*at { | symbol, createIfMissing = true |
		var synthTree;
		synthTree = this.nameSpaces[this.server, symbol];
		if (synthTree.isNil and: createIfMissing) {
			postf("Making new SynthTree for server %, name %\n",
				this.server, symbol);
			synthTree = this.new(symbol);
			this.nameSpaces[this.server, symbol] = synthTree;
			this.root[synthTree.name] = synthTree;
			this.changed(\newSynthTree, synthTree);
		};
		^synthTree;
	}

	*onServer { | argServer |
		// Return synthtrees created on a given server
		argServer = argServer ?? { this.server };
		^this.nameSpaces.at(argServer);
	}

	*new { | name |
		^super.new.init(name);
	}

	init { | argName |
		name = argName;
		args = SynthTreeArgs(this);
	}

	asString {
		^format("%:%", if (template.isNil) { "-" } { template.name }, name);
	}

	*nameSpaces {
		^nameSpaces ?? { nameSpaces = MultiLevelIdentityDictionary() };
	}

	server { ^ this.group.server }

	*server { ^ this.root.group.server }
	*root { ^ ~root ? default }

	*stopAll { | startNode, argFadeTime = 0.1 |
		(startNode ? default).stopAll;
	}

	stopAll { | argFadeTime = 0.1 |
		this.fadeOut(argFadeTime);
		this do: _.stopAll(argFadeTime);
	}

	*initTree { | remakeInputs = false |
		default.notStopped = true;
		default.initTree(remakeInputs);
	}

    initTree { | remakeInputs = false |
		/*  Restart all synths.
			If the server just booted, then also allocate busses.
			This is run by default whenever the server boots.
			Otherwise it can be used to restart the entire tree's synths.
			Only nodes that have "notStopped" set to true will be restarted.
			Safety: If a synth is already running, do not restart.
		*/

		if (remakeInputs) { this.remakeInputs; };
		if (synth.isPlaying) { synth.free };
        if (notStopped) {
            this.makeSynth;
            this do: _.initTree(remakeInputs);
        }
    }

	isPlaying { ^synth.isPlaying }

	remakeInputs {
		/* Remake input busses when a server reboots. Called by initTree */
		var server, bus;
		if (inputs.isNil) { ^this };
		server = this.server;
		// keysValuesDo could produce errors because of operating
		// on array being modified
		inputs.keys do: { | key |
			bus = inputs[key];
			inputs[key] = Bus.audio(server, bus.numChannels);
		};
	}

	asSynthTree { /* ^this */ }

	chuckMakingInput { | synthOrTemplate, replaceAction = \fadeOut,
		argFadeTime, startWhen = \now |
		this.makeInputs();
		this.chuck(synthOrTemplate, replaceAction, argFadeTime, startWhen);
	}

	makeInputs { | specs, numChans |
		var server, bus;
		server = this.server;
		numChans ?? { numChans = ~numChans };
		specs = specs ?? { [in: numChans] };
		switch (specs.class,
			Integer, { specs = [in: specs] },
			Symbol, { specs = [specs, numChans] }
		);
		inputs = inputs ?? { IdentityDictionary(); };
		specs keysValuesDo: { | key, numChannels |
			bus = inputs[key];
			if (bus.isNil) {
				inputs[key] = Bus.audio(server, numChannels);
			}{
				if (bus.numChannels != numChannels) {
					bus.free;
					inputs[key] = Bus.audio(server, numChannels);
				}
			}
		};
	}

	trig { | ... someArgs |
		// restart, ending previous synth if running
		someArgs.keysValuesDo({ | key, value |
			args.storeArgValue(key, value);
		});
		this.chuck;
	}

	receiveChuck { | chucker, replaceAction |
		this.chuck(chucker, replaceAction);
	}

	setPatternDuration { | numberOrPattern |
		[this, thisMethod.name, "not yet implemented"].postln;
	}

	chuck { | argTemplate, numChans |
		/*  Set my template.  Start synth. Replace previous one. */
		notStopped = true;
		template = (argTemplate ? template).asSynthTemplate(name);
		this.makeInputs(template.inputSpecs, numChans);
		this.makeArgs(template.templateArgs);
		if (synth.isPlaying) {
			this.endSynth(\fadeOut, this.getFadeTime);
		};
		this.makeSynth;
		this.push;
	}

	makeArgs { | templateArgs |
		templateArgs do: { | cn |
			args.getParam(cn.name, nil, cn.defaultValue);
		};
	}
	
	getFadeTime { | argFadeTime |
		^argFadeTime ?? { fadeTime ?? { ~fadeTime }};
	}

	push {
		args.parent[\st] = this;
		if (inputs.size > 0) { args.parent[\fx] = this; };
		args.push;		// currentEnvironment = args; 
		this.changed(\chuck);
		postf("~st set to: %\n", this);
	}

	setTemplate { | argTemplate, argReplaceAction = \fadeOut |
		/* set template without starting. Called by ==> operator
			Starting is deferred to after connecting
			self as input to another SynthTree (=<).
			Manner of replacing previous synth is stored in replaceAction */
		replaceAction = argReplaceAction;
		template = argTemplate.asSynthTemplate(name);
		this.makeInputs(template.inputSpecs);
		this.makeArgs(template.templateArgs);
		this.push;
	}

	moveBefore { | argSynth |
		// TODO: move my synth before the output synth
		// and then call moveBeforeOutput on all my inputs sunthTrees
		if (synth.isPlaying) {
			synth.moveBefore(argSynth);
			this do: _.moveBefore(synth);
		};
	}

    makeSynth { | attackTime |
		synth = template.asSynth(this, attackTime);
		// guarantee that moveBefore happens AFTER the synth has really started!
		synth !? {
			synth.onEnd(\this, { // This also registers on NodeWatcher:
				if (this.isPlaying.not) { this.changed(\stopped) };
			}); 
			this.addNotifierOneShot(synth, 'n_go', {
				this do: _.moveBefore(synth);
				this.changed(\started);
			});
		};
		notStopped = true;
	}

	addPatternSynth { | instrument = \default, args |
		/* Make a synth that plays into my synth as event of a PatternInstrument.
			My synth must be a PatternSynth.
			This is mainly for testing PatternInstrument */
		synth.addSynth(instrument, args);
	}

	addInputSynth{ | synthTree, inputName = \in, startWhen = \now |
		/*  Add another synthTree as an input to myself. (I am an "fx" synth).
			Add synthTree to my inputs and make it output its signal to my input.
			Add synthTree to your dictionary under its name,
			THEN create the synth, using your group as target,
			addToHead as add method, and setting the output \out
			to one of your inputs, through args at synth creation time.
		*/
		if (inputs.isNil) {
			postf("% has no inputs. Cannot add input.\n", name);
			^this;
		};
		if (this outputsTo: synthTree) {
			postf("% outputs to % and therefore cannot add it as input. Cycle!\n",
			name, synthTree.name);
			^this
		};
		this[synthTree.name] = synthTree;
		synthTree.setOutput(this, inputName);
		if (startWhen === \now) { synthTree.start };
	}

	outputsTo { | synthTree |
		if (output.isNil) { ^false };
		if (output === synthTree) { ^true } { ^output outputsTo: synthTree };
	}

	setOutput { | synthTree, inputName = \in |
		var outputBus;
		outputBus = synthTree.getInputBus(inputName);
		if (outputBus.isNil) {
			postf("% has no input named %. Cannot output to it\n",
				synthTree.name, inputName);
			^this;
		};
		output = synthTree;
		outputName = inputName;
		if (synth.isPlaying) { synth.set(\out, outputBus.index) }
	}

	getInputBus { | inputName = \in |
		^inputs !? { inputs[inputName] };
	}

	endSynth { | argReplaceAction = \fadeOut, argFadeTime |
		if (argReplaceAction isKindOf: SimpleNumber) {
			synth.fadeOut(argReplaceAction);
		}{
			switch (argReplaceAction,
				\fadeOut, { synth.fadeOut(this getFadeTime: argFadeTime) },
				\free, { synth.free }
			)
		}
	}

	synthArgs {
		/* return argument array for Synth.new / Function.play, containing
			the setters for the output and the input busses */
		var argsArray;
		argsArray = [out: this.getOutputBusIndex, fadeIn: this.getFadeTime];
		inputs !? {
			inputs keysValuesDo: { | key, bus |
				argsArray = argsArray add: key;
				argsArray = argsArray add: bus.index;
			};
		}
		^(args.asArray collect: _.synthArgs ++ argsArray).flat;
	}

	getOutputBusIndex {
		^output.inputBusIndex(outputName) ? 0;
	}

	inputBusIndex { | inputName = \in |
		if (inputs.isNil) { ^0 };
		if (inputs.size < 2) {
			^inputs.asArray.first.index;
		}{
			^inputs[inputName].index;
		}
	}

	toggle { | argFadeTime | 
		if (this.isPlaying) {
			this.fadeOut(this getFadeTime: argFadeTime)
		}{
			this.start(this getFadeTime: argFadeTime)
		}
	}

    start { | attackTime |
		// start, but only if synth is not playing
		if (synth.isPlaying) { } { this.makeSynth(attackTime) };
	}

	release { | argFadeTime |
		if (synth.isPlaying) {
			synth release: this.getFadeTime(argFadeTime);
		// synth.isPlaying = false;
			this.changed(\fadeOut);
		};
		notStopped = false;
	}

	fadeOut { | argFadeTime |
		if (synth.isPlaying) {
			synth.set(\timeScale, this.getFadeTime(argFadeTime), \gate, 0);
			// synth.isPlaying = false;
			this.changed(\fadeOut);
		};
		notStopped = false;
	}

	stop { this.free }

    free {
		if (synth.isPlaying) { synth.free; };
		notStopped = false;
	}

	mute {
		// possibly divert output to a "sink" bus
		[this, thisMethod.name, "not implemented"].postln;
	}

    group {
        ^group ?? {
            if (output.isNil) { Server.default.defaultGroup } { output.group }
        }
    }

	mapSet { | parameter, value |
		// args connect themselves directly to controllers
		// this saves having to access the parameter each time.
		// therefore this method may not be used.
		/* var param;
		param = args[parameter];
		param !? param.mapSet(value);
		*/

		[this, thisMethod.name, parameter, value].postln;
	}

	set { | ... argArgs |
		// Also store parameters in args
		argArgs keysValuesDo: { | key, value |
			args.storeArgValue(key, value);
		};
		if (synth.isPlaying) { synth.set(*argArgs) };
	}

	setSynthParameter { | parameter, value |
		// used by MultiControl etc.
		if (synth.isPlaying) { synth.set(parameter, value); };
	}

	specs { | eventOrArrayOfSpecs |
		// Create argument parameters with their specs
		eventOrArrayOfSpecs keysValuesDo: { | param, spec |
			args.makeParam(param, spec);
		}
	}

	// Controls
	/*
   .out(param = \out, chans = 1) // creates bus ref
   .in(param = \in, chans = 1) // creates bus ref
		.view(param, nameOrView = param) // , storeName = \view
   .osc(param, specs = param, storeName = \osc)
   .buf(name, param, chans) // creates buf ref
   .midi(param, specs, storeName = \midi)
   .map(name, param, chans) // creates bus ref
		.view(param, name, view ...) // name etc. optional. creates knob per default
   // following compose patterns / streams. for later? ... ?
   .add(param, element, storeName, path);
   .sub(param, element, storeName, path);
   .mul(param, element, storeName, path);
   .div(param, element, storeName, path);
   .mod(param, element, storeName, path);
   .pow(param, element, storeName, path);
   .sel(param, element, storeName, path);
   .rej(param, element, storeName, path);
   .fun(param, element, storeName, path);
   .choose(param, element, path);
   .wchoose(param, element, path);
	*/

	playPattern { | patternPlayer |
		// Make patternPlayer my template, and play synths with it
		// TODO !
		[this, thisMethod.name, "Not yet implemented"].postln;
	}
	knobs {
		this.getArgsFromTemplate do: _.addView;
	}

	getArgsFromTemplate {
		^template.templateArgs.reject({ | cName |
			cName.rate === \scalar or: { 
				[\buf, \gate, \out, \in, \in1, \in2, \timeScale, \fadeIn]
				includes: cName.name 
			}
		}) collect: { | cName |
			args.getParam(cName.name, nil, cName.defaultValue);
		};
	}

	view { | param, viewName, view, func, onClose, enabled = true |
		// only param is obligatory. All others are provided by MultiControl
		args.getParam(param)
		.addView(viewName, view, func, onClose, enabled);
	}
	buf { | bufName, param = \buf |
		args.getParam(param).setBuffer(bufName ? param)
	}

	*faders { | argServer |
		var all, panel;
		argServer ?? { argServer = SynthTree.server };
		all = SynthTree.onServer(argServer);
		panel = Sliders.getPanel(argServer.asSymbol);
		panel.window.view.keyDownAction = { | view, char |
			switch (char,
				$b, { BufferList.showList('Create Buffer Player', argServer); },
				$t, { SynthTemplate.gui; },
				$,, { thisProcess.stop },
				$., { SynthTree.stopAll },
				$I, { SynthTree.initTree },
				$/, { SynthTree.initTree },
				$0, { 0.02 =!> \fadeTime },
				$1, { 1 =!> \fadeTime },
				$2, { 2 =!> \fadeTime },
				$3, { 3 =!> \fadeTime },
				$4, { 4 =!> \fadeTime },
				$5, { 5 =!> \fadeTime },
				$6, { 6 =!> \fadeTime },
				$7, { 7 =!> \fadeTime },
				$8, { 8 =!> \fadeTime },
				$9, { 9 =!> \fadeTime },
			);
		};
		all.keys.asArray.select({ | name | name != \root }).sort
		do: { | name | all[name].prFader(panel) };
		panel.addNotifier(this, \newSynthTree, { | synthTree |
			synthTree.prFader(panel);
		});
		^panel;
	}

	prFader { | panel |
		/* Make a fader for amp on a Sliders panel. 
			Note: This is a private class.  It is called via notification
			whenever a new SynthTree is created and an amp faders panel is open.
		*/
		var param, widget, label;
		panel = panel ?? { Sliders.getPanel(this.server.asSymbol) };
		param = args.getParam(\amp);
		widget = panel.widgetFor(this);
		label = widget.label;
		label.addNotifier(this, \chuck, {
			panel.setSelection(label, this);
		});
		label.canReceiveDragHandler = {
			var drag;
			drag = View.currentDrag;
			drag isKindOf: Template or: 
			{ drag isKindOf: SynthTree and: 
				{ this.hasInputs; }
				and:
				{ this hasNoCycles: drag }
			}
		};
		label.receiveDragHandler = {
			var drag;
			drag = View.currentDrag;
			switch (drag.class,
				SynthTemplate, { drag.template => this },
				SynthTree, { this =< drag }
			);
		};
		label.focusGainedAction = {
			if (not(~st === this)) { this.push };
		};
		param.addView(\fader,
			param.connectParamView(widget.slider)
		);
	}

	hasInputs { ^inputs.size > 0 }

	hasNoCycles { | potentialInput |
		^not(this === potentialInput) 
		and: {
			output.isNil 
			or: { output hasNoCycles: potentialInput }
		};
	}

	/*
	// under development
	map { | param, curve | 
		/*  Fade any parameter to any value(s) using a line or envelope ugen
           on a control bus, mapped to the parameter. See MultiControl:map for details.
		*/
		this.getParam(param).map(curve);
	}
	*/

	bufferList {
		// From BufferList. Select buffer from list and play it
		var buffers, keys;
		buffers = Library.at(this.server);
		keys = buffers.keys.asArray.select({ | b | buffers[b].path.notNil }).sort;
		Windows.for(this, \bufferList, { | window |
			var list;
			window.view.layout = VLayout(
				list = ListView().items_(keys);
			);
			list.action = { | me | window.changed(\buffer, me.item); };
			list.keyDownAction = { | view, char, modifiers, unicode, keycode, key |
				switch (char,
					13.asAscii, {
						if (this.isPlaying) {
							this.stop;
						}{
							{ \buf.playBuf } => this.buf(view.item)
							.set(\amp, 1)
							.set(\loop, if (modifiers == 0) { 0 } { 1 });
						}
					},
					Char.space, { Library.at(this.server, view.item).play; },
					$l, { SynthTree.faders; },
					{ view.defaultKeyDownAction(
						char, modifiers, unicode, keycode, key) 
					}
				)
			};
		});
	}

	// Emacs interaction

	*chuckSelectingSynthTree { | argServer |
		/* Eval current snippet or org section as function and chuck into 
		synthtree selected or input interactively in Emacs. */
		Emacs.selectEvalSnippet(
			this.synthTreeNames(argServer),
			"{ %s } => '%s'",
			"Chuck snippet into SynthTree (default: %s): "
		)
	}

	*knobsSelectingSynthTree { | argServer |
		/* Select a synthtree in Emacs and show its knobs window. */
		Emacs.selectEval(
			this.synthTreeNames(argServer),
			"'%s'.knobs",
			"Chuck snippet into SynthTree (default: %s): "
		)
	}

	*toggleSelectingSynthTree { | argServer |
		/* Toggle run status (start/stop) of a synthtree interactively
			selected in Emacs.  Selection defaults to last selected synthtree . */
		Emacs.selectEval(
			this.synthTreeNames(argServer),
			"'%s'.toggle",
			"Toggle SynthTree (default: %s): ",
			true
		)
	}

	*startSelectingSynthTree { | argServer |
		/* Start a synthtree interactively
			selected in Emacs.  Selection defaults to last selected synthtree . */
		Emacs.selectEval(
			this.synthTreeNames(argServer),
			"'%s'.start",
			"Start SynthTree (default: %s): ",
			true
		)
	}

	*fadeOutSelectingSynthTree { | argServer, fadeTime |
		/* Fadeout a synthtree interactively
			selected in Emacs.  Selection defaults to last selected synthtree . 
		Universal (C-U) argument value specifies fadeout time in seconds. */
		Emacs.selectEval(
			this.synthTreeNames(argServer),
			"'%s'" ++ format(".fadeOut(%)", if (fadeTime.isNil) { "" } { fadeTime }),
			"Fadeout SynthTree (default: %s): ",
			true
		)
	}

	*synthTreeNames { | argServer, removeRoot = true |
		var names;
		names = SynthTree.onServer(argServer).asArray collect: _.name;
		if (removeRoot) { names remove: \root };
		^names;
	}
	
}