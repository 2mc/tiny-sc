+ Object {

	objectClosed {
		this.changed(\objectClosed);
		Notification.removeNotifiersOf(this);
		Notification.removeListenersOf(this);
		this.releaseDependants;
	}
}

+ Node {
    /* always release notified nodes when they are freed
        Note: any objects that want to be notified of the node's end, 
        can listen to it notifying 'n_end', which is triggered through NodeWatcher
        and which is the same message that makes the Node remove all its Notifications.
    */
    addNotifier { | notifier, message, action |
        super.addNotifier(notifier, message, action);
        NodeWatcher.register(this);
        this.addNotifierOneShot(this, 'n_end', { this.objectClosed });
    }
}
