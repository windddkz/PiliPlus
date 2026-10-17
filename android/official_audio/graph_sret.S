.text
.global graph_make_frame
.type graph_make_frame,%function
graph_make_frame:
    // Original C++ strong-pointer result uses x8, unlike a C pointer return.
    mov x8, x0
    mov x0, x1
    mov x1, x2
    mov x2, x3
    mov x3, x4
    br x5
.size graph_make_frame, .-graph_make_frame
.global graph_make_decoder
.type graph_make_decoder,%function
graph_make_decoder:
    mov x8, x0
    br x1
.size graph_make_decoder, .-graph_make_decoder
.global media_dash_dump_entry
.type media_dash_dump_entry,%function
media_dash_dump_entry:
    mov x1, sp
    mov x2, x30
    b media_dash_dump
.size media_dash_dump_entry, .-media_dash_dump_entry
.section .note.GNU-stack,"",%progbits
