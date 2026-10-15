// Small reproducible source-design fixture; no transport or wrapped netlist.
module partition_smoke_lane (
    input wire clk, input wire rst,
    input wire [15:0] data, output reg [15:0] value
);
    wire [15:0] permuted = {data[6:0], data[15:7]} ^ {data[3:0], data[15:4]};
    always @(posedge clk)
        if (rst) value <= 16'h0001;
        else value <= permuted + 16'h9e37;
endmodule

module partition_smoke (
    input wire clk, input wire rst, input wire [15:0] seed,
    output wire [15:0] signature
);
    wire [15:0] stage [0:15];
    genvar i;
    generate for (i = 0; i < 16; i = i + 1) begin: lanes
        if (i == 0) begin: first
            partition_smoke_lane lane(clk, rst, seed ^ stage[15], stage[i]);
        end else begin: following
            partition_smoke_lane lane(clk, rst, stage[i-1], stage[i]);
        end
    end endgenerate
    assign signature = stage[15] ^ stage[7] ^ stage[3];
endmodule
