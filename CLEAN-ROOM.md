# How MetroKeys was made

MetroKeys is an independent re-implementation of the look and behaviour of the Windows Phone 8.1 keyboard. It
contains no code, data, images, sounds or fonts of the original keyboard.

The work was split into two separate roles. One role described the behaviour and appearance of the original
keyboard in a written functional specification (what the keyboard does, measurements, layouts and colours). The
implementation was then written from that specification and from public, freely licensed sources only, in separate
working sessions that never had access to anything else. The access of every implementation session was logged and
checked. The specification and the audit log are kept by the author.

The data files the build needs (keyboard layouts, colours, metrics and the emoji page structure) are in
the specification (`spec/data/`, not published).
